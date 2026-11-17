//! Keep recently offered files alive for applications that read CF_HDROP asynchronously.
use std::io;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

const RETENTION: Duration = Duration::from_secs(24 * 60 * 60);
const MAX_BYTES: u64 = 256 * 1024 * 1024;
const MAX_FILES: usize = 256;

pub fn write(root: &Path, png: &[u8], name: &str) -> io::Result<PathBuf> {
    write_at(root, png, name, SystemTime::now())
}

fn write_at(root: &Path, png: &[u8], name: &str, now: SystemTime) -> io::Result<PathBuf> {
    if Path::new(name).file_name().and_then(|n| n.to_str()) != Some(name)
        || name.contains(['/', '\\'])
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid paste filename",
        ));
    }
    std::fs::create_dir_all(root)?;
    let mut bytes = 0u64;
    let mut files = 0usize;
    for entry in std::fs::read_dir(root)? {
        let entry = entry?;
        if !entry.file_type()?.is_dir()
            || !entry.file_name().to_string_lossy().starts_with("paste-")
        {
            continue;
        }
        let dir = entry.path();
        if entry
            .metadata()?
            .modified()
            .ok()
            .and_then(|t| now.duration_since(t).ok())
            .is_some_and(|age| age >= RETENTION)
            && std::fs::remove_dir_all(&dir).is_ok()
        {
            continue;
        }
        files += 1;
        for file in std::fs::read_dir(dir)? {
            let metadata = file?.metadata()?;
            bytes = bytes.saturating_add(metadata.len());
        }
    }
    if files >= MAX_FILES || bytes.saturating_add(png.len() as u64) > MAX_BYTES {
        // Fall back to bitmap-only paste instead of invalidating a recently offered file.
        return Err(io::Error::other("paste file cache is full"));
    }
    let dir = tempfile::Builder::new().prefix("paste-").tempdir_in(root)?;
    let path = dir.path().join(name);
    std::fs::write(&path, png)?;
    let _ = dir.keep();
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn consecutive_pastes_preserve_files_even_with_the_same_name() {
        let root = tempfile::tempdir().unwrap();
        let first = write(root.path(), b"first", "image.png").unwrap();
        let second = write(root.path(), b"second", "image.png").unwrap();
        assert_ne!(first, second);
        assert_eq!(std::fs::read(first).unwrap(), b"first");
        assert_eq!(std::fs::read(second).unwrap(), b"second");
        assert!(write(root.path(), b"bad", "../escape.png").is_err());
    }

    #[test]
    fn full_cache_keeps_recent_files_and_expired_directories_are_reclaimed() {
        let root = tempfile::tempdir().unwrap();
        for i in 0..MAX_FILES {
            std::fs::create_dir(root.path().join(format!("paste-{i}"))).unwrap();
        }
        assert!(write(root.path(), b"new", "image.png").is_err());
        let old = root.path().join("paste-0");
        assert!(
            write_at(
                root.path(),
                b"new",
                "image.png",
                SystemTime::now() + RETENTION + Duration::from_secs(1)
            )
            .is_ok()
        );
        assert!(!old.exists());
    }
}
