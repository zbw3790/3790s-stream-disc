package com.zbw3790.streamdiscs.core;

import java.net.URI;
import java.util.regex.Pattern;

public record MediaTarget(String bvid, int page) {
    private static final Pattern BV = Pattern.compile("BV[0-9A-Za-z]{10}");
    public MediaTarget {
        if (!BV.matcher(bvid).matches() || page < 1 || page > 1000) throw new IllegalArgumentException("INPUT: invalid BV or page");
    }
    public static MediaTarget parse(String input) {
        if (input == null || input.length() > 2048) throw new IllegalArgumentException("INPUT: input too long");
        if (BV.matcher(input).matches()) return new MediaTarget(input, 1);
        try {
            URI uri = URI.create(input);
            if (!"https".equals(uri.getScheme()) || !("www.bilibili.com".equals(uri.getHost()) || "bilibili.com".equals(uri.getHost()))
                    || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("INPUT: only standard HTTPS Bilibili video URLs are supported");
            String path = uri.getPath();
            if (!path.matches("/video/BV[0-9A-Za-z]{10}/?")) throw new IllegalArgumentException("INPUT: invalid video path");
            int page = 1;
            boolean seen = false;
            if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
                if (pair.startsWith("p=")) {
                    if (seen || !pair.substring(2).matches("[1-9][0-9]{0,3}")) throw new IllegalArgumentException("INPUT: invalid page");
                    page = Integer.parseInt(pair.substring(2)); seen = true;
                } else if (pair.equals("p")) throw new IllegalArgumentException("INPUT: invalid page");
            }
            return new MediaTarget(path.substring(7, 19), page);
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("INPUT: invalid BV or supported URL", e); }
    }
    public String identity() { return "bilibili:" + bvid + ":p" + page; }
}
