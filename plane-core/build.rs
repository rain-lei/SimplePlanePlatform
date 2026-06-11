//! 构建脚本。
//!
//! A1 阶段无特殊构建步骤，保留此文件作为后续（如生成绑定、链接 native 库）的扩展点。
//! 显式声明：仅当 build.rs 自身变化时才重新运行，避免无谓的重编译。

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
}
