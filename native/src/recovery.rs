//! Keep damaged history and its images together, including across interrupted recovery.
use crate::{atomic_file, db};
use rusqlite::Connection;
use std::path::{Path, PathBuf};

const PENDING: &str = "recovery-pending.json";
const FILES: &[&str] = &[
    "clipkeep.db",
    "clipkeep.db-wal",
    "clipkeep.db-shm",
    "images",
    "thumbs",
];

pub fn open(data_dir: &Path) -> Result<(Connection, Option<PathBuf>), String> {
    let pending = data_dir.join(PENDING);
    let mut archived = None;
    if pending.exists() {
        archived = Some(finish(data_dir)?);
    }
    match db::open(&data_dir.join("clipkeep.db")) {
        Ok(conn) => Ok((conn, archived)),
        Err(rusqlite::Error::SqliteFailure(error, _))
            if matches!(
                error.code,
                rusqlite::ErrorCode::DatabaseCorrupt | rusqlite::ErrorCode::NotADatabase
            ) =>
        {
            let archive = tempfile::Builder::new()
                .prefix("recovery-")
                .tempdir_in(data_dir)
                .map_err(|e| format!("无法创建历史恢复目录：{e}"))?
                .keep();
            let name = archive
                .file_name()
                .and_then(|s| s.to_str())
                .ok_or("恢复目录名称无效")?;
            atomic_file::save(
                &pending,
                &serde_json::to_vec(name).map_err(|e| e.to_string())?,
            )
            .map_err(|e| format!("无法记录历史恢复进度：{e}"))?;
            let archive = finish(data_dir)?;
            let conn = db::open(&data_dir.join("clipkeep.db")).map_err(|e| e.to_string())?;
            Ok((conn, Some(archive)))
        }
        Err(error) => Err(format!("打开历史数据库失败，原文件已保留：{error}")),
    }
}

fn finish(data_dir: &Path) -> Result<PathBuf, String> {
    let pending = data_dir.join(PENDING);
    let name: String = serde_json::from_slice(&std::fs::read(&pending).map_err(|e| e.to_string())?)
        .map_err(|e| format!("历史恢复进度损坏，原文件已保留：{e}"))?;
    if !name.starts_with("recovery-")
        || !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
    {
        return Err("历史恢复目录名称无效，原文件已保留".into());
    }
    let archive = data_dir.join(name);
    let metadata = std::fs::symlink_metadata(&archive).map_err(|e| e.to_string())?;
    if !metadata.is_dir() || crate::images::is_reparse_point(&archive) {
        return Err("历史恢复目录无效，原文件已保留".into());
    }
    for name in FILES {
        let source = data_dir.join(name);
        if source.try_exists().map_err(|e| e.to_string())? {
            let destination = archive.join(name);
            if destination.try_exists().map_err(|e| e.to_string())? {
                return Err(format!("恢复目录已存在 {name}，请检查后重试；原文件已保留"));
            }
            std::fs::rename(source, destination)
                .map_err(|e| format!("无法保留历史文件 {name}：{e}"))?;
        }
    }
    std::fs::remove_file(pending).map_err(|e| e.to_string())?;
    Ok(archive)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::images::{self, ImageStore, Rgba};

    #[test]
    fn corruption_keeps_images_outside_new_database_gc_across_restarts() {
        let dir = tempfile::tempdir().unwrap();
        let store = ImageStore::new(dir.path()).unwrap();
        let image = Rgba {
            width: 1,
            height: 1,
            pixels: vec![1, 2, 3, 255],
        };
        let saved = store.write(&image, &images::hash(&image)).unwrap();
        let file = saved.file.clone();
        drop(saved);
        std::fs::write(dir.path().join("clipkeep.db"), b"damaged history").unwrap();
        let (conn, archive) = open(dir.path()).unwrap();
        let archive = archive.unwrap();
        ImageStore::new(dir.path())
            .unwrap()
            .collect_garbage(&db::referenced_files(&conn).unwrap());
        assert_eq!(
            std::fs::read(archive.join("clipkeep.db")).unwrap(),
            b"damaged history"
        );
        assert_eq!(
            ImageStore::new(&archive)
                .unwrap()
                .load(&file)
                .unwrap()
                .pixels,
            image.pixels
        );
        drop(conn);
        let (conn, archived) = open(dir.path()).unwrap();
        assert!(archived.is_none());
        ImageStore::new(dir.path())
            .unwrap()
            .collect_garbage(&db::referenced_files(&conn).unwrap());
        assert!(ImageStore::new(&archive).unwrap().load(&file).is_ok());
    }

    #[test]
    fn interrupted_archive_resumes_before_creating_a_database() {
        let dir = tempfile::tempdir().unwrap();
        let archive = dir.path().join("recovery-test");
        std::fs::create_dir(&archive).unwrap();
        std::fs::write(archive.join("clipkeep.db"), b"already moved").unwrap();
        std::fs::create_dir(dir.path().join("images")).unwrap();
        std::fs::write(dir.path().join("images/original"), b"keep").unwrap();
        atomic_file::save(&dir.path().join(PENDING), br#""recovery-test""#).unwrap();
        let (_, archived) = open(dir.path()).unwrap();
        assert_eq!(archived, Some(archive.clone()));
        assert_eq!(
            std::fs::read(archive.join("images/original")).unwrap(),
            b"keep"
        );
        assert!(!dir.path().join(PENDING).exists());
    }

    #[test]
    fn archive_failure_keeps_progress_and_never_creates_an_empty_database() {
        let dir = tempfile::tempdir().unwrap();
        let archive = dir.path().join("recovery-test");
        std::fs::create_dir(&archive).unwrap();
        std::fs::write(archive.join("clipkeep.db"), b"already moved").unwrap();
        std::fs::create_dir(archive.join("images")).unwrap();
        std::fs::create_dir(dir.path().join("images")).unwrap();
        std::fs::write(dir.path().join("images/original"), b"keep").unwrap();
        atomic_file::save(&dir.path().join(PENDING), br#""recovery-test""#).unwrap();
        assert!(open(dir.path()).is_err());
        assert!(!dir.path().join("clipkeep.db").exists());
        assert!(dir.path().join(PENDING).exists());
        assert_eq!(
            std::fs::read(dir.path().join("images/original")).unwrap(),
            b"keep"
        );
        // Once the conflicting empty directory is removed, the same journal can resume.
        std::fs::remove_dir(archive.join("images")).unwrap();
        assert!(open(dir.path()).is_ok());
        assert_eq!(
            std::fs::read(archive.join("images/original")).unwrap(),
            b"keep"
        );
    }

    #[test]
    fn newer_schema_is_not_archived() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("clipkeep.db");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch("PRAGMA user_version=99").unwrap();
        drop(conn);
        assert!(open(dir.path()).is_err());
        assert!(!dir.path().join(PENDING).exists());
        assert_eq!(
            Connection::open(path)
                .unwrap()
                .query_row("PRAGMA user_version", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            99
        );
    }
}
