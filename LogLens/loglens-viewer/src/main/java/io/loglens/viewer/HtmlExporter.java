package io.loglens.viewer;

import io.loglens.storage.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded self-contained report; embedded data cannot terminate the JSON script element. */
public final class HtmlExporter {
    private HtmlExporter() {}
    public static void export(RecordRepository repository, Path destination, int maxRecords, int maxBytes) throws IOException {
        if (maxRecords < 1 || maxRecords > 10000 || maxBytes < 1024 || maxBytes > 64 * 1024 * 1024) throw new IllegalArgumentException("invalid export limits");
        StringBuilder data = new StringBuilder("[");
        long cursor = 0; int count = 0; boolean first = true; long dataBytes = 2;
        while (count < maxRecords) {
            var page = repository.records(RecordQuery.page(cursor, Math.min(500, maxRecords - count)));
            for (var record : page.items()) {
                String json = safeJson(JsonCodec.string(record));
                dataBytes += json.getBytes(StandardCharsets.UTF_8).length + (first ? 0 : 1);
                if (dataBytes > maxBytes) throw new IOException("export exceeds byte limit");
                if (!first) data.append(','); data.append(json); first = false; count++;
            }
            cursor = page.nextCursor();
            if (!page.hasMore() || page.items().isEmpty()) break;
        }
        data.append(']');
        String html = """
                <!doctype html><html lang="zh-CN"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>LogLens 离线报告</title>
                <style>body{background:#181a1b;color:#e6e3db;font:15px Georgia,serif;margin:32px}h1{color:#eab75b}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:13px Consolas,monospace}article{border-top:1px solid #414441;padding:16px 0}button{padding:8px;background:#eab75b;border:0}#status{color:#b9b6ad}</style>
                <h1>LogLens / 离线报告</h1><p id="status"></p><main id="records"></main><button id="more">加载更多</button>
                <script id="data" type="application/json">DATA_PLACEHOLDER</script>
                <script>
                const records=JSON.parse(document.getElementById('data').textContent);let offset=0;
                document.getElementById('status').textContent='导出 '+records.length+' 条记录；报告受记录数和大小上限限制。';
                function show(){const root=document.getElementById('records');root.replaceChildren();
                for(const record of records.slice(offset,offset+100)){const row=document.createElement('article');const title=document.createElement('strong');
                title.textContent=record.level+' · '+record.logger+' · ×'+record.repeatCount;const text=document.createElement('pre');
                text.textContent=record.message+(record.exception?'\\n'+JSON.stringify(record.exception,null,2):'');row.append(title,text);root.append(row)}
                document.getElementById('more').disabled=offset+100>=records.length;}
                document.getElementById('more').onclick=()=>{offset+=100;show();};show();
                </script></html>
                """.replace("DATA_PLACEHOLDER", data);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IOException("export exceeds total byte limit");
        Files.write(destination, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    static String safeJson(String json) {
        return json.replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
}
