package cn.qihangerp.common;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一返回类封装
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
public class R<T> extends HashMap<String, Object> {

    private static final long serialVersionUID = 563554414843661955L;

    public R() {
        put("code", 0);
        put("msg", "success");
    }

    public static <T> R<T> error(int code, String msg) {
        R<T> r = new R<>();
        r.put("code", code);
        r.put("msg", msg);
        r.put("success", false);
        return r;
    }

    public static <T> R<T> success(T data, String msg) {
        R<T> r = new R<>();
        r.put("code", 200);
        r.put("data", data);
        r.put("msg", msg);
        r.put("success", true);
        return r;
    }

    public static <T> R<T> success(T data) {
        return success(data, "success");
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.put("code", 200);
        r.put("data", data);
        r.put("success", true);
        return r;
    }

    public static <T> R<T> ok(String msg) {
        R<T> r = new R<>();
        r.put("msg", msg);
        return r;
    }

    public static <T> R<T> ok(Map<String, Object> map) {
        R<T> r = new R<>();
        r.putAll(map);
        return r;
    }

    public static <T> R<T> ok() {
        return new R<>();
    }

    public R<T> put(String key, Object value) {
        super.put(key, value);
        return this;
    }
}
