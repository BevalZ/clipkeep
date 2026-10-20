//! Reproducible import peak-RSS comparison against the former eager decode pipeline.
use base64::{Engine as _, engine::general_purpose::STANDARD};
use clipkeep_core_tests::{backup, images};
use image::{ExtendedColorType, ImageEncoder, codecs::png::PngEncoder};
use std::{
    fs::File,
    io::{BufWriter, Write},
    path::Path,
};

fn main() {
    let args: Vec<_> = std::env::args().collect();
    let path = Path::new(&args[2]);
    match args[1].as_str() {
        "generate" => {
            let count: usize = args.get(3).map(|n| n.parse().unwrap()).unwrap_or(64);
            let mut writer = BufWriter::new(File::create(path).unwrap());
            writer
                .write_all(b"{\"app\":\"clipkeep\",\"version\":1,\"groups\":[],\"clips\":[")
                .unwrap();
            for n in 0..count {
                let mut pixels = vec![255u8; 1024 * 1024 * 4];
                for pixel in pixels.as_chunks_mut::<4>().0 {
                    pixel[0] = n as u8;
                    pixel[1] = (n >> 8) as u8;
                }
                let mut png = Vec::new();
                PngEncoder::new(&mut png)
                    .write_image(&pixels, 1024, 1024, ExtendedColorType::Rgba8)
                    .unwrap();
                let clip = backup::BundleClip {
                    kind: "image".into(),
                    text: None,
                    image_b64: Some(STANDARD.encode(png)),
                    favorite: false,
                    pinned: false,
                    group_id: None,
                    created_at: n as i64,
                    updated_at: n as i64,
                };
                if n > 0 {
                    writer.write_all(b",").unwrap();
                }
                serde_json::to_writer(&mut writer, &clip).unwrap();
            }
            writer.write_all(b"]}").unwrap();
            writer.flush().unwrap();
        }
        "legacy" => {
            #[derive(serde::Deserialize)]
            struct Bundle {
                clips: Vec<backup::BundleClip>,
            }
            let data = std::fs::read(path).unwrap();
            let bundle: Bundle = serde_json::from_slice(&data).unwrap();
            drop(data);
            let mut pending = Vec::new();
            for clip in bundle.clips {
                let png = STANDARD.decode(clip.image_b64.unwrap()).unwrap();
                pending.push(images::decode_png(&png).unwrap());
            }
            println!(
                "retained {} decoded images",
                std::hint::black_box(&pending).len()
            );
        }
        "stream" => {
            let directory = tempfile::tempdir().unwrap();
            let staged = backup::stage(File::open(path).unwrap(), directory.path()).unwrap();
            println!("staged {} images", staged.count);
        }
        _ => panic!("usage: import_memory generate|legacy|stream <backup> [image-count]"),
    }
}
