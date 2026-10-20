//! Disk-backed import staging: memory grows with one record, not with the backup.
use crate::{db, images};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use serde::{
    Deserialize, Serialize,
    de::{self, DeserializeSeed, MapAccess, SeqAccess, Visitor},
};
use std::{
    fmt,
    fs::File,
    io::{BufReader, BufWriter, Read, Seek, Write},
    path::Path,
};

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BundleGroup {
    pub id: i64,
    pub name: String,
    pub sort_order: i64,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BundleClip {
    pub kind: String,
    pub text: Option<String>,
    pub image_b64: Option<String>,
    pub favorite: bool,
    pub pinned: bool,
    pub group_id: Option<i64>,
    pub created_at: i64,
    pub updated_at: i64,
}

struct Clips<'a, F>(&'a mut F);
impl<'de, F: FnMut(BundleClip) -> Result<(), String>> DeserializeSeed<'de> for Clips<'_, F> {
    type Value = ();
    fn deserialize<D: de::Deserializer<'de>>(self, d: D) -> Result<(), D::Error> {
        d.deserialize_seq(self)
    }
}
impl<'de, F: FnMut(BundleClip) -> Result<(), String>> Visitor<'de> for Clips<'_, F> {
    type Value = ();
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("clips array")
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<(), A::Error> {
        while let Some(clip) = seq.next_element::<BundleClip>()? {
            (self.0)(clip).map_err(de::Error::custom)?;
        }
        Ok(())
    }
}
struct BundleVisitor<F>(F);
impl<'de, F: FnMut(BundleClip) -> Result<(), String>> Visitor<'de> for BundleVisitor<F> {
    type Value = Vec<BundleGroup>;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("clipkeep backup")
    }
    fn visit_map<A: MapAccess<'de>>(mut self, mut map: A) -> Result<Self::Value, A::Error> {
        let (mut app, mut version, mut groups, mut clips) = (None, None, None, false);
        while let Some(key) = map.next_key::<String>()? {
            match key.as_str() {
                "app" if app.is_none() => app = Some(map.next_value::<String>()?),
                "version" if version.is_none() => version = Some(map.next_value::<u32>()?),
                "groups" if groups.is_none() => groups = Some(map.next_value()?),
                "clips" if !clips => {
                    map.next_value_seed(Clips(&mut self.0))?;
                    clips = true;
                }
                "app" | "version" | "groups" | "clips" => {
                    return Err(de::Error::custom("重复的备份字段"));
                }
                _ => {
                    map.next_value::<de::IgnoredAny>()?;
                }
            }
        }
        if app.as_deref() != Some("clipkeep") || version != Some(1) || !clips {
            return Err(de::Error::custom("备份标识或版本无效"));
        }
        groups.ok_or_else(|| de::Error::missing_field("groups"))
    }
}

pub fn read(
    reader: impl Read,
    on_clip: impl FnMut(BundleClip) -> Result<(), String>,
) -> Result<Vec<BundleGroup>, String> {
    let mut deserializer = serde_json::Deserializer::from_reader(reader);
    let groups =
        serde::de::Deserializer::deserialize_map(&mut deserializer, BundleVisitor(on_clip))
            .map_err(|e| e.to_string())?;
    deserializer.end().map_err(|e| e.to_string())?;
    Ok(groups)
}

pub struct Staged {
    // Close the spool before deleting the directory (also works on Windows).
    pub rows: File,
    pub groups: Vec<BundleGroup>,
    pub images: images::ImageStore,
    pub count: usize,
    _directory: tempfile::TempDir,
}

pub fn stage(reader: impl Read, data_dir: &Path) -> Result<Staged, String> {
    let directory = tempfile::Builder::new()
        .prefix("import-")
        .tempdir_in(data_dir)
        .map_err(|e| e.to_string())?;
    let images = images::ImageStore::new(directory.path()).map_err(|e| e.to_string())?;
    let mut rows = tempfile::tempfile_in(directory.path()).map_err(|e| e.to_string())?;
    let mut writer = BufWriter::new(&mut rows);
    let mut count = 0;
    let groups = read(BufReader::new(reader), |clip| {
        let (kind, content, hash, width, height, size) =
            match (clip.kind.as_str(), clip.text, clip.image_b64) {
                (db::KIND_TEXT, Some(text), _) => {
                    let hash = db::text_hash(&text);
                    let size = text.len() as i64;
                    (db::KIND_TEXT, text, hash, None, None, size)
                }
                (db::KIND_IMAGE, _, Some(encoded)) => {
                    let _work = images::IMAGE_WORK.lock().map_err(|e| e.to_string())?;
                    let png = STANDARD.decode(&encoded).map_err(|_| "图片数据损坏")?;
                    drop(encoded);
                    let rgba = images::decode_png(&png)?;
                    drop(png);
                    let hash = images::hash(&rgba);
                    let saved = images.write(&rgba, &hash)?;
                    (
                        db::KIND_IMAGE,
                        saved.file,
                        hash,
                        Some(saved.width as i64),
                        Some(saved.height as i64),
                        saved.bytes as i64,
                    )
                }
                _ => return Err("备份条目类型或内容无效".into()),
            };
        let row = db::RawClip {
            kind: kind.into(),
            content,
            hash,
            width,
            height,
            size,
            favorite: clip.favorite,
            pinned: clip.pinned,
            group_id: clip.group_id,
            created_at: clip.created_at,
            updated_at: clip.updated_at,
        };
        serde_json::to_writer(&mut writer, &row).map_err(|e| e.to_string())?;
        writer.write_all(b"\n").map_err(|e| e.to_string())?;
        count += 1;
        Ok(())
    })?;
    writer.flush().map_err(|e| e.to_string())?;
    drop(writer);
    rows.rewind().map_err(|e| e.to_string())?;
    Ok(Staged {
        rows,
        groups,
        images,
        count,
        _directory: directory,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn accepts_field_order_and_rejects_trailing_or_duplicate_data() {
        let valid = r#"{"clips":[],"groups":[],"version":1,"app":"clipkeep"}"#;
        assert!(read(valid.as_bytes(), |_| Ok(())).is_ok());
        for bad in [
            format!("{valid}x"),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"groups\":[]", "\"groups\":[],\"groups\":[]"),
        ] {
            assert!(read(bad.as_bytes(), |_| Ok(())).is_err());
        }
    }
    #[test]
    fn stages_one_record_at_a_time_and_cleans_up_on_failure() {
        let directory = tempfile::tempdir().unwrap();
        let clip = r#"{"kind":"text","text":"你好","imageB64":null,"favorite":true,"pinned":false,"groupId":null,"createdAt":1,"updatedAt":2}"#;
        let json =
            format!(r#"{{"app":"clipkeep","version":1,"groups":[],"clips":[{clip},{clip}]}}"#);
        let staged = stage(json.as_bytes(), directory.path()).unwrap();
        assert_eq!(staged.count, 2);
        let rows: Vec<_> = serde_json::Deserializer::from_reader(BufReader::new(&staged.rows))
            .into_iter::<db::RawClip>()
            .collect();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].as_ref().unwrap().content, "你好");
        drop(staged);
        assert_eq!(directory.path().read_dir().unwrap().count(), 0);
        assert!(stage(format!("{json}broken").as_bytes(), directory.path()).is_err());
        assert_eq!(directory.path().read_dir().unwrap().count(), 0);
    }
}
