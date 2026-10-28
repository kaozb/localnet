package com.tinyblock.app;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/**
 * AdGuard 风格黑白名单：
 * - ||example.org^        拦截该域名及所有子域名
 * - @@||example.org^      放行（优先于拦截）
 * - 0.0.0.0 example.org   hosts 格式拦截
 * - example.org           纯域名行拦截
 * - ! / # 开头为注释
 */
public class Rules {

    private final HashSet<String> block = new HashSet<>();
    private final HashSet<String> allow = new HashSet<>();

    public synchronized void clear() {
        block.clear();
        allow.clear();
    }

    public synchronized void loadFile(String path) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) add(line);
        } catch (IOException ignored) {
        }
    }

    public synchronized int blockCount() {
        return block.size();
    }

    private void add(String raw) {
        String line = raw.trim();
        if (line.isEmpty()) return;
        char c0 = line.charAt(0);
        if (c0 == '!' || c0 == '#' || line.startsWith("//")) return;

        String low = line.toLowerCase();
        // 修饰类 / 页面元素规则一律跳过
        if (low.contains("##") || low.contains("#@#") || low.contains("#$#")
                || low.contains("#?#") || low.contains("#%#")) return;

        boolean isAllow = low.startsWith("@@");
        if (isAllow) low = low.substring(2);

        String domain;
        if (low.startsWith("||")) {
            domain = cut(low.substring(2));
        } else if (low.startsWith("|") || low.startsWith("$") || low.contains("://")) {
            return; // URL 锚点 / 选项行，不做域名匹配
        } else {
            String[] parts = low.split("\\s+");
            if (parts.length >= 2 && isIp(parts[0])) {
                domain = cut(parts[1]);
            } else if (parts.length == 1) {
                domain = cut(parts[0]);
            } else {
                return;
            }
        }
        if (domain == null) return;
        if (domain.startsWith("*.")) domain = domain.substring(2);
        while (domain.startsWith(".")) domain = domain.substring(1);
        while (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
        if (!validDomain(domain)) return;
        if (isAllow) allow.add(domain);
        else block.add(domain);
    }

    /** 在规则选项、通配符、路径处截断 */
    private static String cut(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '^' || ch == '$' || ch == '/' || ch == '*'
                    || ch == '=' || ch == ',' || ch == ' ' || ch == '#') {
                return s.substring(0, i);
            }
        }
        return s;
    }

    private static boolean isIp(String s) {
        return s.equals("0.0.0.0") || s.equals("127.0.0.1") || s.equals("::1") || s.equals("::");
    }

    private static boolean validDomain(String d) {
        if (d.length() < 4 || !d.contains(".")) return false;
        boolean hasLetter = false;
        for (int i = 0; i < d.length(); i++) {
            char ch = d.charAt(i);
            if (ch >= 'a' && ch <= 'z') hasLetter = true;
            else if (!((ch >= '0' && ch <= '9') || ch == '.' || ch == '-' || ch == '_')) return false;
        }
        // 纯数字加点的是 IP，不是域名
        return hasLetter;
    }

    /** 0 = 未命中, 1 = 拦截, 2 = 放行 */
    public synchronized int match(String host) {
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        String d = host;
        while (d.length() > 0) {
            if (allow.contains(d)) return 2;
            if (block.contains(d)) return 1;
            int i = d.indexOf('.');
            if (i < 0) return 0;
            d = d.substring(i + 1);
        }
        return 0;
    }
}
