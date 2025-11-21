package vice.sol_valheim.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class TextPlural {
    @FunctionalInterface
    private interface PluralRule {
        PluralForm getForm(double count);
    }

    private enum PluralForm {
        ONE,
        FEW,
        MANY,
        OTHER;

        public String getString() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Map<String, PluralRule> RULES = new HashMap<>();

    static {
        RULES.put("en", TextPlural::ruleSimpleOneOther);
        RULES.put("de", TextPlural::ruleSimpleOneOther);
        RULES.put("es", TextPlural::ruleSimpleOneOther);
        RULES.put("it", TextPlural::ruleSimpleOneOther);
        RULES.put("pt", TextPlural::ruleSimpleOneOther);

        RULES.put("tr", TextPlural::ruleWithoutPlural);
        RULES.put("ja", TextPlural::ruleWithoutPlural);
        RULES.put("ko", TextPlural::ruleWithoutPlural);
        RULES.put("zh", TextPlural::ruleWithoutPlural);

        RULES.put("ru", TextPlural::ruleRussian);
        RULES.put("uk", TextPlural::ruleRussian);
        RULES.put("pl", TextPlural::rulePolish);
        RULES.put("fr", TextPlural::ruleFrench);
        RULES.put("cs", TextPlural::ruleCzech);
    }

    private TextPlural() {}

    // -------------------------- PUBLIC API --------------------------

    public static String getString(String baseKey, int count) {
        return translatable(baseKey, count).getString();
    }

    public static String getString(String baseKey, double count) {
        return translatable(baseKey, count).getString();
    }

    public static MutableComponent translatable(String baseKey, int count) {
        return translatable(baseKey, (double) count, null);
    }

    public static MutableComponent translatable(String baseKey, double count) {
        return translatable(baseKey, count, null);
    }

    /**
     * Core method.
     * countStr — optional preformatted count (if null, we format via formatValue)
     */
    public static MutableComponent translatable(String baseKey, double count, String countStr) {
        String lang = getCurrentLanguageCode();
        PluralRule rule = RULES.getOrDefault(lang, RULES.get("en"));
        PluralForm form = rule.getForm(count);

        String pluralKey = form != PluralForm.ONE
            ? String.format("%s.plural_%s", baseKey, form.getString())
            : baseKey;

        String finalKey = null;
        if (I18n.exists(pluralKey)) {
            finalKey = pluralKey;
        } else if (I18n.exists(baseKey)) {
            finalKey = baseKey;
        }

        String formattedCount = (countStr == null || countStr.isEmpty())
            ? formatValue(count, lang, 2)
            : countStr;

        return Component.translatableWithFallback(finalKey, formattedCount + " " + baseKey, count);
    }

    // ----------------------- LANGUAGE RULES -----------------------

    private static PluralForm ruleSimpleOneOther(double n) {
        if (isDouble(n)) return PluralForm.OTHER;
        return (n == 1) ? PluralForm.ONE : PluralForm.OTHER;
    }

    private static PluralForm ruleWithoutPlural(double n) {
        return PluralForm.ONE;
    }

    private static PluralForm ruleFrench(double n) {
        return (n == 0 || n == 1) ? PluralForm.ONE : PluralForm.OTHER;
    }

    private static PluralForm ruleRussian(double value) {
        if (isDouble(value)) return PluralForm.OTHER;
        long n = (long) Math.floor(value);
        long mod10 = n % 10;
        long mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return PluralForm.ONE;
        if (mod10 >= 2 && mod10 <= 4 && !(mod100 >= 12 && mod100 <= 14)) return PluralForm.FEW;
        return PluralForm.MANY;
    }

    private static PluralForm rulePolish(double value) {
        if (isDouble(value)) return PluralForm.OTHER;
        long n = (long) Math.floor(value);
        long mod10 = n % 10;
        long mod100 = n % 100;
        if (n == 1) return PluralForm.ONE;
        if (mod10 >= 2 && mod10 <= 4 && !(mod100 >= 12 && mod100 <= 14)) return PluralForm.FEW;
        return PluralForm.MANY;
    }

    private static PluralForm ruleCzech(double n) {
        if (isDouble(n)) return PluralForm.OTHER;
        if (n == 1) return PluralForm.ONE;
        if (n >= 2 && n <= 4) return PluralForm.FEW;
        return PluralForm.MANY;
    }

    // --------------------------- Utils ---------------------------

    private static boolean isDouble(double v) {
        return !(Math.abs(v - Math.round(v)) < 1e-9);
    }

    private static String getCurrentLanguageCode() {
        try {
            String code = Minecraft.getInstance()
                .getLanguageManager()
                .getSelected()
                .toLowerCase(Locale.ROOT);
            int underscore = code.indexOf('_');
            return (underscore >= 0) ? code.substring(0, underscore) : code;
        } catch (Exception e) {
            return Locale.getDefault().getLanguage();
        }
    }

    private static String formatValue(double value, String lang, int precision) {
        if (!isDouble(value)) {
            return String.valueOf(Math.round(value));
        }

        double scale = Math.pow(10, precision);
        double rounded = Math.round(value * scale) / scale;

        String pattern = "%." + precision + "f";
        String s = String.format(Locale.US, pattern, rounded);

        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            s = s.replaceAll("\\.$", "");
        }

        if ("fr".equals(lang) || "ru".equals(lang) || "de".equals(lang)) {
            s = s.replace('.', ',');
        }

        return s;
    }
}