package cn.qihangerp.agent.memory;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * 敏感信息脱敏工具
 *
 * 支持多种敏感信息类型的脱敏处理：
 * - 手机号
 * - 身份证
 * - 银行卡
 * - 邮箱
 * - 姓名
 *
 * @author AI Code Generator
 * @since 2026-10-12
 */
@Slf4j
public class SensitiveDataMasker {

    // 正则表达式
    private static final Pattern PHONE_PATTERN = Pattern.compile("(1[3-9]\\d)(\\d{4})(\\d{4})");
    private static final Pattern ID_CARD_18_PATTERN = Pattern.compile("(\\d{6})(\\d{8})(\\d{4})");
    private static final Pattern ID_CARD_15_PATTERN = Pattern.compile("(\\d{6})(\\d{6})(\\d{3})");
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile("(\\d{4})(\\d{8,12})(\\d{4})");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("([a-zA-Z0-9]{1,3})[a-zA-Z0-9._-]*@");
    private static final Pattern NAME_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]{1})([\\u4e00-\\u9fa5]+)");

    /**
     * 脱敏手机号
     *
     * 示例：13812341234 -> 138****1234
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return PHONE_PATTERN.matcher(phone).replaceAll("$1****$3");
    }

    /**
     * 脱敏身份证（支持18位和15位旧版）
     *
     * 示例：110101199001011234 -> 110101******1234
     *       110101900101123  -> 110101******123
     */
    public static String maskIdCard(String idCard) {
        if (idCard == null || (idCard.length() != 15 && idCard.length() != 18)) {
            return idCard;
        }
        if (idCard.length() == 15) {
            return ID_CARD_15_PATTERN.matcher(idCard).replaceAll("$1******$3");
        }
        return ID_CARD_18_PATTERN.matcher(idCard).replaceAll("$1******$3");
    }

    /**
     * 脱敏银行卡
     *
     * 示例：6222021234567891234 -> 6222 **** **** 1234
     */
    public static String maskBankCard(String bankCard) {
        if (bankCard == null || bankCard.length() < 16) {
            return bankCard;
        }
        return BANK_CARD_PATTERN.matcher(bankCard).replaceAll("$1 **** **** $3");
    }

    /**
     * 脱敏邮箱
     *
     * 示例：abcdefg@example.com -> abc***@example.com
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        return EMAIL_PATTERN.matcher(email).replaceAll("$1***@");
    }

    /**
     * 脱敏中文姓名
     *
     * 示例：张三 -> 张*   王小明 -> 王**
     */
    public static String maskChineseName(String name) {
        if (name == null || name.length() < 2) {
            return name;
        }
        return NAME_PATTERN.matcher(name).replaceAll("$1" + "*".repeat(name.length() - 1));
    }

    /**
     * 自动检测并脱敏
     *
     * 自动识别文本中的敏感信息并脱敏
     */
    public static String autoMask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text;

        // 按优先级脱敏
        result = PHONE_PATTERN.matcher(result).replaceAll("$1****$3");
        result = ID_CARD_18_PATTERN.matcher(result).replaceAll("$1******$3");
        result = ID_CARD_15_PATTERN.matcher(result).replaceAll("$1******$3");
        result = BANK_CARD_PATTERN.matcher(result).replaceAll("$1 **** **** $3");
        result = EMAIL_PATTERN.matcher(result).replaceAll("$1***@");

        return result;
    }

    /**
     * 检测文本是否包含敏感信息
     */
    public static boolean containsSensitiveInfo(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        return PHONE_PATTERN.matcher(text).find()
                || ID_CARD_18_PATTERN.matcher(text).find()
                || ID_CARD_15_PATTERN.matcher(text).find()
                || BANK_CARD_PATTERN.matcher(text).find()
                || EMAIL_PATTERN.matcher(text).find();
    }

    /**
     * 脱敏类型枚举
     */
    public enum MaskType {
        PHONE("手机号"),
        ID_CARD("身份证"),
        BANK_CARD("银行卡"),
        EMAIL("邮箱"),
        NAME("姓名"),
        AUTO("自动检测");

        private final String displayName;

        MaskType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * 根据类型脱敏
     */
    public static String mask(String value, MaskType type) {
        if (value == null) {
            return null;
        }

        switch (type) {
            case PHONE:
                return maskPhone(value);
            case ID_CARD:
                return maskIdCard(value);
            case BANK_CARD:
                return maskBankCard(value);
            case EMAIL:
                return maskEmail(value);
            case NAME:
                return maskChineseName(value);
            case AUTO:
            default:
                return autoMask(value);
        }
    }
}
