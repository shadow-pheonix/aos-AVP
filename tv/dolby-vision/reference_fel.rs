// SPDX-License-Identifier: Apache-2.0
// The upstream fel_to_81.bin fixture preserves FEL mapping (mode 4). Kodi's
// mode 2 intentionally removes it. Derive the mode-2 reference from that
// already converted Profile 8 fixture, independently of the packet converter.
use dolby_vision::rpu::dovi_rpu::DoviRpu;
use std::{env, fs};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = env::args().collect();
    let mut rpu = DoviRpu::parse_unspec62_nalu(&fs::read(&args[1])?)?;
    assert_eq!(rpu.dovi_profile, 8);
    assert!(rpu.header.disable_residual_flag);
    rpu.remove_mapping();
    let expected = rpu.write_hevc_unspec62_nalu()?;
    let parsed = DoviRpu::parse_unspec62_nalu(&expected)?;
    assert_eq!(parsed.dovi_profile, 8);
    assert!(parsed.header.disable_residual_flag);
    fs::write(&args[2], expected)?;
    Ok(())
}
