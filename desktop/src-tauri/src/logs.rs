//! 实时日志流模块
//! 对标 dashboard 的 SSE 日志推送 + stdout/stderr 捕获
//! 使用环形缓冲区存储最近日志，前端通过轮询或事件获取

use serde::Serialize;
use std::collections::VecDeque;
use std::io::{BufRead, BufReader, Read};
use std::sync::Arc;
use tokio::sync::Mutex;

/// 单条日志
#[derive(Debug, Clone, Serialize)]
pub struct LogEntry {
    pub timestamp: u64,
    pub level: String,
    pub service: String,
    pub message: String,
}

/// 日志管理器 - 环形缓冲区
pub struct LogManager {
    entries: VecDeque<LogEntry>,
    max_entries: usize,
}

impl LogManager {
    pub fn new(max_entries: usize) -> Self {
        Self {
            entries: VecDeque::with_capacity(max_entries),
            max_entries,
        }
    }

    /// 追加一条日志
    pub fn push(&mut self, level: &str, service: &str, message: &str) {
        let entry = LogEntry {
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64,
            level: level.to_string(),
            service: service.to_string(),
            message: message.to_string(),
        };

        if self.entries.len() >= self.max_entries {
            self.entries.pop_front();
        }
        self.entries.push_back(entry);
    }

    /// 获取指定服务的最近 N 条日志
    pub fn get_logs(&self, service: Option<&str>, count: usize) -> Vec<LogEntry> {
        let iter = self.entries.iter().filter(|e| {
            service.is_none_or(|s| e.service == s)
        });

        let filtered: Vec<_> = iter.cloned().collect();
        let start = if filtered.len() > count {
            filtered.len() - count
        } else {
            0
        };
        filtered[start..].to_vec()
    }

    /// 获取自某个时间戳之后的新日志（增量拉取）
    pub fn get_logs_since(&self, since_ts: u64, service: Option<&str>) -> Vec<LogEntry> {
        self.entries
            .iter()
            .filter(|e| e.timestamp > since_ts && service.is_none_or(|s| e.service == s))
            .cloned()
            .collect()
    }

    /// 获取指定服务最近 N 条日志的文本内容（用于错误诊断）
    pub fn get_recent(&self, service: &str, count: usize) -> Vec<String> {
        let filtered: Vec<_> = self
            .entries
            .iter()
            .filter(|e| e.service == service)
            .collect();
        let start = if filtered.len() > count {
            filtered.len() - count
        } else {
            0
        };
        filtered[start..]
            .iter()
            .map(|e| e.message.clone())
            .collect()
    }

    /// 清空指定服务的日志
    pub fn clear(&mut self, service: Option<&str>) {
        if let Some(s) = service {
            self.entries.retain(|e| e.service != s);
        } else {
            self.entries.clear();
        }
    }
}

/// 启动子进程的 stdout/stderr 读取线程，实时将输出写入日志管理器
/// 使用 tokio::spawn 配合 blocking read 避免死锁
pub fn spawn_log_reader<R: Read + Send + 'static>(
    reader_source: R,
    service: String,
    log_manager: Arc<Mutex<LogManager>>,
) {
    // 使用 tokio::task::spawn_blocking 把阻塞IO放到专用线程池
    // 再通过 channel 发回 tokio 异步任务写入日志
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<(String, String)>();

    // 阻塞读线程
    std::thread::spawn(move || {
        let reader = BufReader::new(reader_source);
        for line in reader.lines() {
            match line {
                Ok(text) => {
                    if text.trim().is_empty() {
                        continue;
                    }
                    let level = classify_log_level(&text);
                    // 通过 channel 发送，永不阻塞
                    if tx.send((level, text)).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });

    // 异步接收任务，写入日志管理器
    let svc = service;
    tokio::spawn(async move {
        while let Some((level, text)) = rx.recv().await {
            log_manager.lock().await.push(&level, &svc, &text);
        }
    });
}

/// 从日志内容推断级别
fn classify_log_level(line: &str) -> String {
    let lower = line.to_lowercase();
    if lower.contains("error") || lower.contains("exception") || lower.contains("fatal") {
        "error".to_string()
    } else if lower.contains("warn") {
        "warning".to_string()
    } else if lower.contains("started") || lower.contains("listening") || lower.contains("running") {
        "success".to_string()
    } else {
        "info".to_string()
    }
}
