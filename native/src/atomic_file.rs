use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::Path;

/// Write beside the destination and replace it only after the full file is written and synced.
/// A random, exclusively created temporary file cannot be pre-planted as a link.
pub fn save(path: &Path, contents: &[u8]) -> io::Result<()> {
    save_stream(path, |file| file.write_all(contents))
}

pub fn save_stream(
    path: &Path,
    write: impl FnOnce(&mut fs::File) -> io::Result<()>,
) -> io::Result<()> {
    let mut random = [0u8; 16];
    getrandom::fill(&mut random).map_err(io::Error::other)?;
    let temp = path.with_extension(format!("{:032x}.tmp", u128::from_ne_bytes(random)));
    let result = (|| {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temp)?;
        write(&mut file)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temp, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn replaces_only_after_a_complete_write() {
        let mut random = [0u8; 8];
        getrandom::fill(&mut random).unwrap();
        let dir = std::env::temp_dir().join(format!(
            "clipkeep-atomic-test-{}",
            u64::from_ne_bytes(random)
        ));
        fs::create_dir(&dir).unwrap();
        let path = dir.join("settings.json");
        save(&path, b"first").unwrap();
        save(&path, b"second").unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"second");
        assert!(
            save_stream(&path, |file| {
                file.write_all(b"partial")?;
                Err(io::Error::other("interrupted export"))
            })
            .is_err()
        );
        assert_eq!(fs::read(&path).unwrap(), b"second");
        fs::remove_file(path).unwrap();
        fs::remove_dir(dir).unwrap();
    }
}
