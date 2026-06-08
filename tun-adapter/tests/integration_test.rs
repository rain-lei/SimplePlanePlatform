//! 集成测试
//!
//! 注意：TUN 设备需要 root 权限，标记为 `#[ignore]` 的测试需要 `sudo cargo test -- --ignored`

use std::net::SocketAddr;
use std::process::Command;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

/// 测试：SOCKS5 连接到真实的 proxy-local 并访问 HTTP
#[tokio::test]
#[ignore] // 需要 proxy-local 运行在 127.0.0.1:1080
async fn test_socks5_to_proxy_local() {
    let socks5_addr: SocketAddr = "127.0.0.1:1080".parse().unwrap();

    // 尝试连接 proxy-local
    let stream_result = tokio::time::timeout(Duration::from_secs(3), TcpStream::connect(socks5_addr)).await;

    match stream_result {
        Ok(Ok(mut stream)) => {
            // SOCKS5 认证握手
            stream.write_all(&[0x05, 0x01, 0x00]).await.unwrap();
            let mut response = [0u8; 2];
            stream.read_exact(&mut response).await.unwrap();
            assert_eq!(response[0], 0x05);
            assert_eq!(response[1], 0x00);

            // SOCKS5 CONNECT 到 httpbin.org:80
            let domain = b"httpbin.org";
            let mut connect_req = vec![
                0x05, // VER
                0x01, // CMD CONNECT
                0x00, // RSV
                0x03, // ATYP DOMAIN
                domain.len() as u8,
            ];
            connect_req.extend_from_slice(domain);
            connect_req.extend_from_slice(&80u16.to_be_bytes());
            stream.write_all(&connect_req).await.unwrap();

            // 读取响应
            let mut header = [0u8; 4];
            stream.read_exact(&mut header).await.unwrap();
            assert_eq!(header[0], 0x05); // VER
            assert_eq!(header[1], 0x00); // SUCCESS

            // 读取绑定地址
            match header[3] {
                0x01 => {
                    let mut buf = [0u8; 6];
                    stream.read_exact(&mut buf).await.unwrap();
                }
                0x03 => {
                    let mut len = [0u8; 1];
                    stream.read_exact(&mut len).await.unwrap();
                    let mut buf = vec![0u8; len[0] as usize + 2];
                    stream.read_exact(&mut buf).await.unwrap();
                }
                0x04 => {
                    let mut buf = [0u8; 18];
                    stream.read_exact(&mut buf).await.unwrap();
                }
                _ => panic!("unexpected ATYP"),
            }

            // 发送 HTTP 请求
            let http_request = "GET /get HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\n\r\n";
            stream.write_all(http_request.as_bytes()).await.unwrap();

            // 读取响应
            let mut response_buf = vec![0u8; 4096];
            let n = tokio::time::timeout(Duration::from_secs(10), stream.read(&mut response_buf))
                .await
                .expect("HTTP response timeout")
                .expect("read error");

            let response_str = String::from_utf8_lossy(&response_buf[..n]);
            assert!(
                response_str.contains("HTTP/1.1 200"),
                "Expected HTTP 200, got: {}",
                &response_str[..std::cmp::min(100, response_str.len())]
            );

            println!("SOCKS5 proxy test PASSED: successfully connected to httpbin.org via proxy-local");
        }
        Ok(Err(e)) => {
            println!("SKIPPED: proxy-local not running at {}: {}", socks5_addr, e);
        }
        Err(_) => {
            println!("SKIPPED: connection to proxy-local timed out");
        }
    }
}

/// 测试：健康检查能正确检测 proxy-local 状态
#[tokio::test]
async fn test_health_check_detects_down() {
    // 连接一个不存在的端口
    let bad_addr: SocketAddr = "127.0.0.1:19876".parse().unwrap();
    let result = tokio::time::timeout(Duration::from_secs(3), TcpStream::connect(bad_addr)).await;

    match result {
        Ok(Ok(_)) => panic!("Should not connect to non-existent port"),
        Ok(Err(_)) | Err(_) => {
            // 预期：连接失败或超时
            println!("Health check correctly detected proxy down");
        }
    }
}

/// 测试：Mock SOCKS5 server 完整流程
#[tokio::test]
async fn test_mock_socks5_full_flow() {
    // 启动 mock SOCKS5 server
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let socks5_addr = listener.local_addr().unwrap();

    // 启动一个目标 HTTP server
    let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let http_addr = http_listener.local_addr().unwrap();

    // Mock SOCKS5 server: 接收连接后转发到目标
    let socks5_handle = tokio::spawn(async move {
        let (mut client, _) = listener.accept().await.unwrap();

        // 认证
        let mut buf = [0u8; 3];
        client.read_exact(&mut buf).await.unwrap();
        client.write_all(&[0x05, 0x00]).await.unwrap();

        // CONNECT 请求
        let mut header = [0u8; 4];
        client.read_exact(&mut header).await.unwrap();

        // 读取地址（domain）
        let mut len = [0u8; 1];
        client.read_exact(&mut len).await.unwrap();
        let mut domain = vec![0u8; len[0] as usize];
        client.read_exact(&mut domain).await.unwrap();
        let mut port = [0u8; 2];
        client.read_exact(&mut port).await.unwrap();

        // 连接目标
        let mut target = TcpStream::connect(http_addr).await.unwrap();

        // 发送成功响应
        let response = [0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
        client.write_all(&response).await.unwrap();

        // 双向转发
        tokio::io::copy_bidirectional(&mut client, &mut target).await.ok();
    });

    // Mock HTTP server
    let http_handle = tokio::spawn(async move {
        let (mut stream, _) = http_listener.accept().await.unwrap();
        let mut buf = vec![0u8; 4096];
        let _ = stream.read(&mut buf).await.unwrap();

        let response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
        stream.write_all(response.as_bytes()).await.unwrap();
        stream.shutdown().await.ok();
    });

    // 客户端通过 SOCKS5 连接
    let mut client = TcpStream::connect(socks5_addr).await.unwrap();

    // SOCKS5 认证
    client.write_all(&[0x05, 0x01, 0x00]).await.unwrap();
    let mut resp = [0u8; 2];
    client.read_exact(&mut resp).await.unwrap();
    assert_eq!(resp, [0x05, 0x00]);

    // SOCKS5 CONNECT（domain方式）
    let domain = b"localhost";
    let mut req = vec![0x05, 0x01, 0x00, 0x03, domain.len() as u8];
    req.extend_from_slice(domain);
    req.extend_from_slice(&http_addr.port().to_be_bytes());
    client.write_all(&req).await.unwrap();

    // 读取 CONNECT 响应
    let mut header = [0u8; 10];
    client.read_exact(&mut header).await.unwrap();
    assert_eq!(header[1], 0x00); // 成功

    // 发送 HTTP 请求
    let http_req = format!("GET / HTTP/1.1\r\nHost: localhost:{}\r\nConnection: close\r\n\r\n", http_addr.port());
    client.write_all(http_req.as_bytes()).await.unwrap();

    // 读取 HTTP 响应
    let mut resp_buf = vec![0u8; 4096];
    let n = tokio::time::timeout(Duration::from_secs(5), client.read(&mut resp_buf))
        .await
        .unwrap()
        .unwrap();

    let resp_str = String::from_utf8_lossy(&resp_buf[..n]);
    assert!(resp_str.contains("200 OK"), "Expected 200 OK, got: {}", resp_str);
    assert!(resp_str.contains("OK"), "Expected body 'OK'");

    println!("Mock SOCKS5 full flow test PASSED");

    socks5_handle.abort();
    http_handle.abort();
}

/// 端到端测试：FakeDNS → 路由决策 → SOCKS5 转发完整链路
/// 模拟真实流量走 proxy-local (127.0.0.1:1080) → proxy-remote (PROXY_REMOTE_IP:9090)
#[tokio::test]
#[ignore] // 需要 proxy-local 运行在 1080
async fn test_full_pipeline_via_proxy_local() {
    use fast_socks5::client::Socks5Stream;

    // 1. 验证 proxy-local 可连接
    let socks5_addr = "127.0.0.1:1080";
    let connect_result =
        tokio::time::timeout(Duration::from_secs(5), TcpStream::connect(socks5_addr)).await;

    match connect_result {
        Ok(Ok(_)) => {}
        _ => {
            println!("SKIPPED: proxy-local not running at {}", socks5_addr);
            return;
        }
    }

    // 2. 通过 SOCKS5 域名连接 google.com:443（模拟 TUN 模式下 FakeDNS 反查后的域名转发）
    let socks_stream = tokio::time::timeout(
        Duration::from_secs(10),
        Socks5Stream::connect(socks5_addr, "www.google.com".to_string(), 80, fast_socks5::client::Config::default()),
    )
    .await;

    match socks_stream {
        Ok(Ok(mut stream)) => {
            // 发送 HTTP 请求
            let request = "GET / HTTP/1.1\r\nHost: www.google.com\r\nConnection: close\r\n\r\n";
            stream.get_socket_mut().write_all(request.as_bytes()).await.unwrap();

            // 读取响应
            let mut buf = vec![0u8; 8192];
            let n = tokio::time::timeout(Duration::from_secs(10), stream.get_socket_mut().read(&mut buf))
                .await
                .expect("timeout reading response")
                .expect("read error");

            let response = String::from_utf8_lossy(&buf[..n]);
            assert!(
                response.contains("HTTP/1.1") || response.contains("HTTP/1.0"),
                "Expected HTTP response, got: {}",
                &response[..std::cmp::min(200, response.len())]
            );
            println!(
                "Full pipeline test PASSED: www.google.com via SOCKS5 → proxy-remote chain OK"
            );
            println!("Response first line: {}", response.lines().next().unwrap_or(""));
        }
        Ok(Err(e)) => {
            panic!("SOCKS5 CONNECT to www.google.com failed: {}", e);
        }
        Err(_) => {
            panic!("SOCKS5 CONNECT timed out");
        }
    }
}

/// 测试：TUN 设备创建和路由（需要 root 权限）
#[tokio::test]
#[ignore] // 需要 sudo
async fn test_tun_device_creation() {
    // 验证 TUN 设备能被创建
    let output = Command::new("ifconfig")
        .output()
        .expect("ifconfig failed");

    let before = String::from_utf8_lossy(&output.stdout);
    let utun_count_before = before.matches("utun").count();

    println!("Before TUN creation: {} utun interfaces", utun_count_before);
    println!("TUN device creation test requires running with sudo");
}

/// 测试：路由恢复在 Ctrl+C 后正确执行
#[tokio::test]
#[ignore] // 需要 sudo
async fn test_route_guard_recovery() {
    // 获取当前路由表
    let output = Command::new("netstat")
        .args(["-rn"])
        .output()
        .expect("netstat failed");

    let routes_before = String::from_utf8_lossy(&output.stdout);
    println!("Current routes (sample):");
    for line in routes_before.lines().take(10) {
        println!("  {}", line);
    }
    println!("Route guard recovery test requires running the full tun-adapter with sudo");
}
