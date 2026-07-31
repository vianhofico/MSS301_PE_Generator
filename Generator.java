import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Generator {

    public static void main(String[] args) throws Exception {
        Out.banner();

        List<Path> sqlFiles = new ArrayList<>();
        Path replay = null;
        for (String a : args) {
            Path p = Path.of(a);
            if (!Files.exists(p)) {
                Out.err("Khong tim thay file: " + a);
                return;
            }
            if (a.toLowerCase(Locale.ROOT).endsWith(".txt") && Ask.looksLikeAnswerFile(p)) {
                replay = p;
            } else {
                sqlFiles.add(p);
            }
        }
        if (sqlFiles.isEmpty()) {
            sqlFiles = discoverSql();
        }
        if (sqlFiles.isEmpty()) {
            Out.err("Khong tim thay file SQL nao. Dat file .sql/.txt vao thu muc 'sql/' hoac truyen duong dan lam tham so.");
            return;
        }
        if (replay != null) {
            Ask.loadReplay(replay);
            Out.info("Doc lai dap an tu: " + replay);
        }

        Out.info("File SQL dung lam dau vao:");
        for (Path p : sqlFiles) Out.info("   - " + p);

        StringBuilder all = new StringBuilder();
        for (Path p : sqlFiles) all.append(Files.readString(p, StandardCharsets.UTF_8)).append("\n");

        Schema schema = SqlParser.parse(all.toString());
        if (schema.tables.isEmpty()) {
            Out.err("Khong doc duoc CREATE TABLE nao trong file SQL.");
            return;
        }
        Out.ok("Doc duoc " + schema.tables.size() + " bang: "
                + schema.tables.stream().map(t -> t.sqlName).collect(Collectors.joining(", ")));
        for (Tbl t : schema.tables) Out.detail(t.describe());

        List<String> empty = schema.tables.stream().filter(t -> t.cols.isEmpty())
                .map(t -> t.sqlName).toList();
        if (!empty.isEmpty()) {
            Out.err("Cac bang sau khong doc duoc cot nao: " + String.join(", ", empty));
            Out.hint("Kiem tra lai cu phap CREATE TABLE trong file SQL (xem QUY_TAC_SQL.md).");
            Out.hint("Moi cot phai co dang:  [ten_cot] KIEU(do_dai) [NOT NULL]");
            return;
        }

        Spec spec = Interview.run(schema);

        Path outRoot = Path.of(spec.rootFolder);
        if (Files.exists(outRoot)) {
            String c = Ask.ask("overwrite", "Thu muc '" + spec.rootFolder + "' da ton tai, ghi de", "y");
            if (!c.equalsIgnoreCase("y")) {
                Out.err("Da huy.");
                return;
            }
            deleteTree(outRoot);
        }

        Emit.generate(spec, outRoot);
        Ask.saveAnswers(Path.of("dap-an.txt"));

        Out.done(spec, outRoot);
    }

    static List<Path> discoverSql() throws IOException {
        List<Path> found = new ArrayList<>();
        for (Path dir : new Path[]{Path.of("sql"), Path.of(".")}) {
            if (!Files.isDirectory(dir)) continue;
            try (var s = Files.list(dir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return n.endsWith(".sql") || n.endsWith(".txt");
                        })
                        .filter(p -> {
                            try {
                                return Files.readString(p, StandardCharsets.UTF_8)
                                        .toUpperCase(Locale.ROOT).contains("CREATE TABLE");
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .sorted()
                        .forEach(found::add);
            }
            if (!found.isEmpty()) break;
        }
        return found;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}

// ===================================================================== model

class Col {
    String sqlName;
    String javaName;
    String sqlType = "NVARCHAR";
    int length = 0;
    int precision = 0, scale = 0;
    boolean identity, notNull, unique, primaryKey;
    List<String> enumValues = new ArrayList<>();
    List<String> allowedValues = new ArrayList<>();
    Integer minInt, maxInt;
    boolean positive;
    boolean nonNegative;
    String fkTable, fkColumn;

    String enumClass;
    String javaType;
    boolean computed;
    String regex;
    String jsonPattern;
    /** Gioi han ngay: dateMin/dateMax la ngay tuyet doi "yyyy-MM-dd"; dateMinDays/dateMaxDays la so ngay lech so voi hom nay. */
    String dateMin, dateMax;
    Integer dateMinDays, dateMaxDays;

    boolean isEnum() {
        return !enumValues.isEmpty();
    }

    boolean isString() {
        return "String".equals(javaType);
    }

    boolean isDate() {
        return "Date".equals(javaType);
    }

    boolean isDecimal() {
        return "BigDecimal".equals(javaType);
    }

    boolean isInt() {
        return "Integer".equals(javaType);
    }

    String getter() {
        return "get" + Names.cap(javaName);
    }

    String setter() {
        return "set" + Names.cap(javaName);
    }
}

class Tbl {
    String sqlName;
    String entity;
    List<Col> cols = new ArrayList<>();
    List<String[]> colCompare = new ArrayList<>();
    List<String> unparsedChecks = new ArrayList<>();
    boolean pkGuessed;
    int extraFkCount;
    int extraUniqueCount;

    Col pk;
    Col uniqueCol;
    Col statusCol;
    Col fkCol;
    Tbl parent;

    Svc svc;
    boolean crossService;

    String var;
    String basePath;
    String controllerClass;
    String defaultStatus;
    String deleteStatus;
    List<Col> filters = new ArrayList<>();
    Set<String> partialFilters = new LinkedHashSet<>();
    boolean hasDetailDto;
    boolean nestedInMain;
    String requiredParentStatus;
    String[] parentCompare;
    Col computedCol;
    int computeMode;
    Col priceCol;
    Col multiplierCol;

    Col byName(String sqlName) {
        for (Col c : cols) if (c.sqlName.equalsIgnoreCase(sqlName)) return c;
        return null;
    }

    /** Chi giu cac cap so sanh ma CA HAI ve deu la cot ngay - phan con lai khong sinh code duoc. */
    List<String[]> dateCompares() {
        List<String[]> out = new ArrayList<>();
        for (String[] cc : colCompare) {
            Col a = byName(cc[0]), b = byName(cc[2]);
            if (a != null && b != null && a.isDate() && b.isDate()) out.add(cc);
        }
        return out;
    }

    boolean hasDateBound() {
        for (Col c : cols) {
            if (c.isDate() && (c.dateMin != null || c.dateMax != null
                    || c.dateMinDays != null || c.dateMaxDays != null)) return true;
        }
        return false;
    }

    boolean usesDateUtils() {
        return !dateCompares().isEmpty() || computeMode == 1 || hasDateBound();
    }

    String describe() {
        StringBuilder sb = new StringBuilder("   " + sqlName + " (" + cols.size() + " cot)");
        for (Col c : cols) {
            sb.append("\n      - ").append(c.sqlName).append(" : ").append(c.sqlType);
            if (c.length > 0) sb.append("(").append(c.length).append(")");
            if (c.precision > 0) sb.append("(").append(c.precision).append(",").append(c.scale).append(")");
            if (c.identity) sb.append(" IDENTITY");
            if (c.notNull) sb.append(" NOT NULL");
            if (c.unique) sb.append(" UNIQUE");
            if (c.isEnum()) sb.append(" ENUM").append(c.enumValues);
            if (c.minInt != null || c.maxInt != null) sb.append(" range[").append(c.minInt).append("..").append(c.maxInt).append("]");
            if (c.positive) sb.append(" >0");
            if (c.fkTable != null) sb.append(" FK->").append(c.fkTable);
        }
        for (String[] cc : this.colCompare) {
            sb.append("\n      * rule: ").append(cc[0]).append(" ").append(cc[1]).append(" ").append(cc[2]);
        }
        return sb.toString();
    }
}

class Svc {
    String project;
    String pkg;
    int port;
    List<Tbl> tables = new ArrayList<>();
    Set<Svc> feignTargets = new LinkedHashSet<>();
    boolean needsDateUtils;
    /** Ten class da dung trong package 'entity' cua service nay (entity + enum). */
    Set<String> usedEntityNames = new LinkedHashSet<>();
}

class Schema {
    String dbName = "MSS301_2026_PE";
    List<Tbl> tables = new ArrayList<>();

    Tbl byName(String n) {
        for (Tbl t : tables) if (t.sqlName.equalsIgnoreCase(n)) return t;
        return null;
    }
}

class Spec {
    String studentId;
    String studentIdLower;
    String rootFolder;
    String bootVersion, cloudVersion, springdocVersion;
    String dbName, dbHost, dbUser, dbPass;
    String gatewayProject;
    int gatewayPort;
    Schema schema;
    List<Svc> services = new ArrayList<>();
    Map<String, int[]> codes = new LinkedHashMap<>();
    int[] gValidation, gDuplicated, gNotFound, gNotAvailable;
    int statusSuccess, statusServerError;
    String timezone;
    int defaultPageSize, maxPageSize;

    int[] code(String key, int[] fallback) {
        return codes.getOrDefault(key, fallback);
    }
}

// ==================================================================== parser

class SqlParser {

    static Schema parse(String raw) {
        String sql = stripComments(raw);
        Schema schema = new Schema();

        Matcher use = Pattern.compile("USE\\s+\\[?([A-Za-z0-9_]+)\\]?", Pattern.CASE_INSENSITIVE).matcher(sql);
        if (use.find()) schema.dbName = use.group(1);

        Matcher ct = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:\\[?[A-Za-z0-9_]+\\]?\\s*\\.\\s*)?\\[?([A-Za-z0-9_]+)\\]?\\s*\\(",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        while (ct.find()) {
            String name = ct.group(1);
            int open = sql.indexOf('(', ct.end() - 1);
            String body = balanced(sql, open);
            if (body == null) continue;
            Tbl t = new Tbl();
            t.sqlName = name;
            parseBody(t, body);
            schema.tables.add(t);
        }

        parseAlters(schema, sql);

        for (Tbl t : schema.tables) {
            for (Col c : t.cols) {
                String plainName = Names.camel(c.sqlName);
                c.javaName = Names.safeIdent(plainName, "Value");
                if (!c.javaName.equals(plainName)) {
                    Out.warn("Cot '" + c.sqlName + "' trung tu khoa Java -> field dat ten '"
                            + c.javaName + "' (@Column van giu dung ten SQL).");
                }
                c.javaType = javaType(c);
                if (c.fkTable != null) c.javaType = "Long";
            }
            for (Col c : t.cols) {
                if (c.primaryKey || c.identity) {
                    t.pk = c;
                    c.javaType = "Long";
                }
            }
            if (t.pk == null && !t.cols.isEmpty()) {
                t.pk = t.cols.get(0);
                t.pk.javaType = "Long";
                t.pkGuessed = true;
            }
            for (Col c : t.cols) {
                if (c.isEnum() && !c.enumValues.stream().allMatch(Names::isJavaIdent)) {
                    c.allowedValues = new ArrayList<>(c.enumValues);
                    c.enumValues.clear();
                    c.javaType = "String";
                }
            }
            int fkCount = 0, uniqueCount = 0;
            for (Col c : t.cols) {
                if (c.unique && !c.primaryKey) {
                    uniqueCount++;
                    if (t.uniqueCol == null) t.uniqueCol = c;
                }
                if (c.fkTable != null) {
                    fkCount++;
                    if (t.fkCol == null) t.fkCol = c;
                }
            }
            t.extraFkCount = Math.max(0, fkCount - 1);
            t.extraUniqueCount = Math.max(0, uniqueCount - 1);
            for (Col c : t.cols) {
                if (!c.isEnum()) continue;
                String n = c.sqlName.toLowerCase(Locale.ROOT);
                if (n.equals("status")) { t.statusCol = c; break; }
                if (t.statusCol == null && (n.contains("status") || n.contains("state"))) t.statusCol = c;
            }
        }
        for (Tbl t : schema.tables) {
            if (t.fkCol != null) t.parent = schema.byName(t.fkCol.fkTable);
        }
        return schema;
    }

    static void parseBody(Tbl t, String body) {
        for (String part : splitTopLevel(body)) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            String up = s.toUpperCase(Locale.ROOT);
            if (up.startsWith("CONSTRAINT") || up.startsWith("PRIMARY KEY") || up.startsWith("UNIQUE")
                    || up.startsWith("CHECK") || up.startsWith("FOREIGN KEY")) {
                applyConstraint(t, s);
            } else {
                Col c = parseColumn(s);
                if (c != null) t.cols.add(c);
            }
        }
    }

    static Col parseColumn(String s) {
        Matcher m = Pattern.compile(
                "^\\[?([A-Za-z0-9_]+)\\]?\\s+\\[?([A-Za-z0-9_]+)\\]?\\s*(\\(([^)]*)\\))?(.*)$",
                Pattern.DOTALL).matcher(s.trim());
        if (!m.find()) return null;
        String name = m.group(1);
        String type = m.group(2);
        if (type.equalsIgnoreCase("KEY") || type.equalsIgnoreCase("TABLE")) return null;
        Col c = new Col();
        c.sqlName = name;
        c.sqlType = type.toUpperCase(Locale.ROOT);
        String argsRaw = m.group(4);
        if (argsRaw != null && !argsRaw.isBlank()) {
            String[] parts = argsRaw.split(",");
            try {
                if (parts.length == 1) {
                    if (!parts[0].trim().equalsIgnoreCase("MAX")) c.length = Integer.parseInt(parts[0].trim());
                } else {
                    c.precision = Integer.parseInt(parts[0].trim());
                    c.scale = Integer.parseInt(parts[1].trim());
                }
            } catch (NumberFormatException ignored) {
            }
        }
        String rest = (m.group(5) == null ? "" : m.group(5));
        String restUp = rest.toUpperCase(Locale.ROOT);
        c.identity = restUp.contains("IDENTITY");
        c.notNull = restUp.contains("NOT NULL");
        if (restUp.contains("PRIMARY KEY")) {
            c.primaryKey = true;
            c.notNull = true;
        }
        if (restUp.matches("(?s).*\\bUNIQUE\\b.*")) c.unique = true;
        Matcher fk = Pattern.compile(
                "REFERENCES\\s+(?:\\[?[A-Za-z0-9_]+\\]?\\s*\\.\\s*)?\\[?([A-Za-z0-9_]+)\\]?\\s*\\(\\s*\\[?([A-Za-z0-9_]+)\\]?\\s*\\)",
                Pattern.CASE_INSENSITIVE).matcher(rest);
        if (fk.find()) {
            c.fkTable = fk.group(1);
            c.fkColumn = fk.group(2);
        }
        return c;
    }

    static void applyConstraint(Tbl t, String s) {
        String up = s.toUpperCase(Locale.ROOT);

        Matcher pk = Pattern.compile("PRIMARY\\s+KEY[^(]*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE).matcher(s);
        if (pk.find()) {
            for (String cn : firstIdents(pk.group(1))) markPk(t, cn);
            return;
        }
        if (up.contains("FOREIGN KEY")) {
            Matcher fk = Pattern.compile(
                    "FOREIGN\\s+KEY\\s*\\(\\s*\\[?([A-Za-z0-9_]+)\\]?\\s*\\)\\s*REFERENCES\\s+(?:\\[?[A-Za-z0-9_]+\\]?\\s*\\.\\s*)?\\[?([A-Za-z0-9_]+)\\]?\\s*\\(\\s*\\[?([A-Za-z0-9_]+)\\]?\\s*\\)",
                    Pattern.CASE_INSENSITIVE).matcher(s);
            if (fk.find()) {
                Col c = t.byName(fk.group(1));
                if (c != null) {
                    c.fkTable = fk.group(2);
                    c.fkColumn = fk.group(3);
                }
            }
            return;
        }
        Matcher uq = Pattern.compile("UNIQUE[^(]*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE).matcher(s);
        if (uq.find() && !up.contains("CHECK")) {
            for (String cn : firstIdents(uq.group(1))) {
                Col c = t.byName(cn);
                if (c != null) c.unique = true;
            }
            return;
        }
        int ci = up.indexOf("CHECK");
        if (ci >= 0) {
            int open = s.indexOf('(', ci);
            if (open < 0) return;
            String expr = balanced(s, open);
            if (expr == null) return;
            if (!applyCheck(t, expr)) {
                t.unparsedChecks.add(expr.trim().replaceAll("\\s+", " "));
            }
        }
    }

    /** Tra ve true neu rut duoc it nhat 1 thong tin; false = khong hieu -> se bao cho nguoi dung. */
    static boolean applyCheck(Tbl t, String expr) {
        boolean got = false;

        Matcher in = Pattern.compile("\\[?([A-Za-z0-9_]+)\\]?\\s+IN\\s*\\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE).matcher(expr);
        while (in.find()) {
            Col c = t.byName(in.group(1));
            if (c == null) continue;
            Matcher lit = Pattern.compile("N?'([^']*)'").matcher(in.group(2));
            while (lit.find()) {
                if (!c.enumValues.contains(lit.group(1))) c.enumValues.add(lit.group(1));
                got = true;
            }
        }
        if (got) return true;

        Matcher orForm = Pattern.compile("\\[?([A-Za-z0-9_]+)\\]?\\s*=\\s*N?'([^']*)'",
                Pattern.CASE_INSENSITIVE).matcher(expr);
        Map<String, List<String>> orValues = new LinkedHashMap<>();
        while (orForm.find()) {
            orValues.computeIfAbsent(orForm.group(1).toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(orForm.group(2));
        }
        for (Map.Entry<String, List<String>> e : orValues.entrySet()) {
            if (e.getValue().size() < 2) continue;
            Col c = t.byName(e.getKey());
            if (c == null || !c.enumValues.isEmpty()) continue;
            c.enumValues.addAll(e.getValue());
            got = true;
        }
        if (got) return true;

        Matcher between = Pattern.compile("\\[?([A-Za-z0-9_]+)\\]?\\s+BETWEEN\\s+(-?[0-9.]+)\\s+AND\\s+(-?[0-9.]+)",
                Pattern.CASE_INSENSITIVE).matcher(expr);
        while (between.find()) {
            Col c = t.byName(between.group(1));
            if (c == null) continue;
            try {
                c.minInt = (int) Double.parseDouble(between.group(2));
                c.maxInt = (int) Double.parseDouble(between.group(3));
                got = true;
            } catch (NumberFormatException ignored) {
            }
        }
        if (got) return true;

        Matcher len = Pattern.compile("LEN\\s*\\(\\s*\\[?([A-Za-z0-9_]+)\\]?\\s*\\)\\s*(<=|<|=)\\s*([0-9]+)",
                Pattern.CASE_INSENSITIVE).matcher(expr);
        while (len.find()) {
            Col c = t.byName(len.group(1));
            if (c == null) continue;
            try {
                int n = Integer.parseInt(len.group(3));
                c.length = "<".equals(len.group(2)) ? n - 1 : n;
                got = true;
            } catch (NumberFormatException ignored) {
            }
        }
        if (got) return true;

        Matcher cmp2 = Pattern.compile("\\[?([A-Za-z0-9_]+)\\]?\\s*(>=|<=|>|<)\\s*\\[([A-Za-z0-9_]+)\\]").matcher(expr);
        while (cmp2.find()) {
            Col a = t.byName(cmp2.group(1));
            Col b = t.byName(cmp2.group(3));
            if (a != null && b != null) {
                t.colCompare.add(new String[]{a.sqlName, cmp2.group(2), b.sqlName});
                got = true;
            }
        }
        if (got) return true;

        Matcher num = Pattern.compile("\\[?([A-Za-z0-9_]+)\\]?\\s*(>=|<=|>|<)\\s*\\(?\\s*(-?[0-9.]+)\\s*\\)?").matcher(expr);
        while (num.find()) {
            Col c = t.byName(num.group(1));
            if (c == null) continue;
            String op = num.group(2);
            double v;
            try {
                v = Double.parseDouble(num.group(3));
            } catch (NumberFormatException e) {
                continue;
            }
            got = true;
            switch (op) {
                case ">=" -> {
                    if (v == 0) c.nonNegative = true;
                    c.minInt = (int) v;
                }
                case ">" -> {
                    if (v == 0) c.positive = true;
                    else c.minInt = (int) v + 1;
                }
                case "<=" -> c.maxInt = (int) v;
                case "<" -> c.maxInt = (int) v - 1;
                default -> {
                }
            }
        }
        return got;
    }

    static void parseAlters(Schema schema, String sql) {
        Matcher m = Pattern.compile(
                "ALTER\\s+TABLE\\s+(?:\\[?[A-Za-z0-9_]+\\]?\\s*\\.\\s*)?\\[?([A-Za-z0-9_]+)\\]?(.*?)(?=ALTER\\s+TABLE|CREATE\\s+TABLE|\\z)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql);
        while (m.find()) {
            Tbl t = schema.byName(m.group(1));
            if (t == null) continue;
            String seg = m.group(2);
            if (!Pattern.compile("ADD\\s+CONSTRAINT", Pattern.CASE_INSENSITIVE).matcher(seg).find()) continue;
            applyConstraint(t, seg);
        }
    }

    static void markPk(Tbl t, String colName) {
        Col c = t.byName(colName);
        if (c != null) {
            c.primaryKey = true;
            c.notNull = true;
        }
    }

    static List<String> firstIdents(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\[?([A-Za-z0-9_]+)\\]?").matcher(s);
        while (m.find()) {
            String g = m.group(1);
            if (g.equalsIgnoreCase("ASC") || g.equalsIgnoreCase("DESC")) continue;
            if (!g.isBlank()) out.add(g);
        }
        return out;
    }

    static String javaType(Col c) {
        if (c.isEnum()) return "ENUM";
        return switch (c.sqlType) {
            case "INT", "INTEGER", "SMALLINT", "TINYINT" -> "Integer";
            case "BIGINT" -> "Long";
            case "DECIMAL", "NUMERIC", "MONEY", "SMALLMONEY" -> "BigDecimal";
            case "FLOAT", "REAL" -> "Double";
            case "BIT" -> "Boolean";
            case "DATE", "DATETIME", "DATETIME2", "SMALLDATETIME", "DATETIMEOFFSET" -> "Date";
            default -> "String";
        };
    }

    static String stripComments(String s) {
        s = s.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("--[^\\n]*", " ");
        return s;
    }

    static String balanced(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '(') return null;
        int depth = 0;
        boolean inStr = false;
        for (int i = openIdx; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'') inStr = !inStr;
            if (inStr) continue;
            if (ch == '(') depth++;
            else if (ch == ')') {
                depth--;
                if (depth == 0) return s.substring(openIdx + 1, i);
            }
        }
        return null;
    }

    static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\'') inStr = !inStr;
            if (!inStr) {
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                else if (ch == ',' && depth == 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(ch);
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }
}

// ================================================================= interview

class Interview {

    static Spec run(Schema schema) {
        Spec s = new Spec();
        s.schema = schema;

        Out.section("0. RA SOAT RANG BUOC DOC TU SQL");
        reviewConstraints(schema);

        Out.section("1. THONG TIN CHUNG");
        s.studentId = Ask.askRequired("studentId", "Ma sinh vien (vd HE181534)");
        s.studentIdLower = s.studentId.toLowerCase(Locale.ROOT);
        s.rootFolder = Ask.ask("rootFolder", "Ten thu muc goc (folder se zip nop)", s.studentId + "_PE");
        s.bootVersion = Ask.ask("bootVersion",
                "Spring Boot version (doc dung trong de, muc Framework)", "3.5.16");
        s.cloudVersion = Ask.ask("cloudVersion",
                "Spring Cloud version (phai khop Boot version)", "2025.0.0");
        s.springdocVersion = Ask.ask("springdocVersion", "SpringDoc version", "2.8.8");

        Out.section("2. DATABASE");
        s.dbName = Ask.ask("dbName",
                "Ten database (dung dung ten trong de, phan biet hoa/thuong)", schema.dbName);
        s.dbHost = Ask.ask("dbHost", "Host:port SQL Server", "localhost:1433");
        s.dbUser = Ask.ask("dbUser", "Username", "sa");
        s.dbPass = Ask.ask("dbPass", "Password", "sa");

        Out.section("2b. QUY UOC CHUNG");
        s.timezone = Ask.ask("timezone", "Timezone cho ngay/gio (dung trong @JsonFormat va TimeZone.setDefault)", "GMT+7");
        s.defaultPageSize = Ask.askInt("defaultPageSize", "Page size mac dinh khi khong truyen 'size'", 10);
        s.maxPageSize = Ask.askInt("maxPageSize", "Page size toi da (client truyen size lon hon se bi cat ve muc nay)", 100);

        Out.section("3. CHIA SERVICE");
        Out.hint("Hai bang muon nam chung 1 service thi dat cung mot ten project.");
        Map<String, Svc> byProject = new LinkedHashMap<>();
        int nextPort = 8081;
        for (Tbl t : schema.tables) {
            String k = t.sqlName;
            t.entity = Ask.ask(k + ".entity", "  Bang '" + t.sqlName + "' -> ten class Entity",
                    Names.safeIdent(Names.pascal(Names.singular(t.sqlName)), "Entity"));
            t.entity = Names.safeIdent(t.entity, "Entity");
            t.var = Names.safeIdent(Names.camel(t.entity), "Item");
            String defProject = s.studentId + t.entity + "Service";
            String project = Ask.ask(k + ".project", "  Bang '" + t.sqlName + "' -> project", defProject);
            Svc svc = byProject.get(project);
            if (svc == null) {
                svc = new Svc();
                svc.project = project;
                svc.pkg = "fu." + s.studentIdLower + "." + Names.lower(t.entity);
                svc.port = Ask.askRequiredInt(k + ".port",
                        "  Project '" + project + "' -> port (goi y " + nextPort + ", doc dung trong de)");
                nextPort = svc.port + 1;
                byProject.put(project, svc);
                s.services.add(svc);
            }
            svc.tables.add(t);
            t.svc = svc;
        }
        for (Svc svc : s.services) {
            svc.pkg = Ask.ask(svc.project + ".pkg", "  Package goc cua '" + svc.project + "'", svc.pkg);
        }

        s.gatewayProject = Ask.askRequired("gatewayProject",
                "Ten project Gateway (dung dung ten trong de, vd " + s.studentId + "HotelGateway hoac "
                        + s.studentId + "EmployeeGateway)");
        s.gatewayPort = Ask.askRequiredInt("gatewayPort", "Port Gateway (goi y 8080, doc dung trong de)");

        Out.section("4. TUNG ENTITY");
        for (Svc svc : s.services) {
            for (Tbl t : svc.tables) {
                t.entity = uniqueClassName(svc.usedEntityNames, t.entity, "entity bang " + t.sqlName);
                t.var = Names.safeIdent(Names.camel(t.entity), "Item");
            }
        }
        for (Tbl t : schema.tables) {
            Out.hint("--- " + t.entity + " (bang " + t.sqlName + ") ---");
            t.basePath = Ask.ask(t.sqlName + ".path", "  Duong dan API",
                    "/api/" + Names.lower(Names.plural(t.entity)));
            t.controllerClass = Ask.ask(t.sqlName + ".controller", "  Ten class Controller",
                    s.studentId + t.entity + "Controller");

            for (Col c : t.cols) {
                if (c.isEnum() && c.enumClass == null) {
                    c.enumClass = uniqueClassName(t.svc.usedEntityNames, enumClassName(t, c),
                            "enum cot " + c.sqlName);
                }
            }
            for (Col c : t.cols) {
                if (c.javaName.equals("page") || c.javaName.equals("size")) {
                    Out.warn("  Cot '" + c.javaName + "' trung ten tham so phan trang - bo khoi danh sach loc"
                            + " neu khong se loi bien trung ten.");
                }
            }

            String enumCols = t.cols.stream().filter(Col::isEnum).map(c -> c.javaName)
                    .collect(Collectors.joining(", "));
            if (!enumCols.isEmpty()) Out.hint("  Cot enum: " + enumCols);
            String statusAns = Ask.ask(t.sqlName + ".statusCol",
                    "  Cot trang thai (dung cho gia tri mac dinh + DELETE, de trong = khong co)",
                    t.statusCol != null ? t.statusCol.javaName : "");
            t.statusCol = null;
            if (!statusAns.isBlank()) {
                for (Col c : t.cols) {
                    if (c.isEnum() && c.javaName.equalsIgnoreCase(statusAns.trim())) t.statusCol = c;
                }
                if (t.statusCol == null) Out.warn("    Khong co cot enum ten '" + statusAns + "' - bo qua.");
            }

            if (t.statusCol != null) {
                List<String> ev = t.statusCol.enumValues;
                t.defaultStatus = pickEnumValue(Ask.ask(t.sqlName + ".defaultStatus",
                        "  Gia tri " + t.statusCol.javaName + " mac dinh khi tao " + ev, ev.get(0)), ev, ev.get(0));
                String del = Ask.ask(t.sqlName + ".deleteStatus",
                        "  DELETE -> set " + t.statusCol.javaName + " thanh (de trong = xoa han)",
                        ev.get(ev.size() - 1));
                t.deleteStatus = del.isBlank() ? "" : pickEnumValue(del, ev, "");
            } else {
                t.deleteStatus = "";
            }

            String defFilter = t.cols.stream().filter(Col::isEnum).map(c -> c.javaName)
                    .collect(Collectors.joining(","));
            Out.hint("  Field co the loc: " + t.cols.stream().filter(c -> !c.identity && !c.primaryKey)
                    .map(c -> c.javaName).collect(Collectors.joining(", ")));
            String filters = Ask.ask(t.sqlName + ".filters",
                    "  Query param loc GET list (them * de partial match)", defFilter);
            for (String f : filters.split(",")) {
                String name = f.trim();
                if (name.isEmpty()) continue;
                boolean partial = name.endsWith("*");
                if (partial) name = name.substring(0, name.length() - 1).trim();
                Col c = null;
                for (Col cc : t.cols) if (cc.javaName.equalsIgnoreCase(name)) c = cc;
                if (c == null) {
                    Out.warn("    Bo qua '" + name + "' - khong co cot nay.");
                    continue;
                }
                t.filters.add(c);
                if (partial) t.partialFilters.add(c.javaName);
            }

            for (Col c : t.cols) {
                if (c.primaryKey || c.identity || c.isEnum() || !c.isInt()) continue;
                String cur = (c.minInt == null ? "" : c.minInt) + ".." + (c.maxInt == null ? "" : c.maxInt);
                String r = Ask.ask(t.sqlName + "." + c.sqlName + ".range",
                        "  Range cho " + c.javaName + " (dang min..max)", cur);
                String[] pr = r.split("\\.\\.", -1);
                c.minInt = pr.length > 0 && !pr[0].isBlank() ? Integer.parseInt(pr[0].trim()) : null;
                c.maxInt = pr.length > 1 && !pr[1].isBlank() ? Integer.parseInt(pr[1].trim()) : null;
            }

            for (Col c : t.cols) {
                if (!c.isDate()) continue;
                String def = "DATE".equals(c.sqlType) ? "dd/MM/yyyy" : "dd/MM/yyyy HH:mm:ss";
                c.jsonPattern = Ask.ask(t.sqlName + "." + c.sqlName + ".pattern",
                        "  Dinh dang JSON cho " + c.javaName + " (" + c.sqlType + ")", def);
            }

            for (Col c : t.cols) {
                if (!c.isString() || c.primaryKey) continue;
                c.regex = Ask.ask(t.sqlName + "." + c.sqlName + ".regex",
                        "  " + c.javaName + " co bat buoc dinh dang rieng (regex, de trong = khong)", "");
            }
        }

        Out.section("5. LIEN KET GIUA SERVICE (Feign)");
        Out.hint("Neu file SQL khong khai bao FOREIGN KEY, tu khai bao o day (de bai van coi la khoa ngoai).");
        for (Tbl t : schema.tables) {
            String detected = t.fkCol != null ? t.fkCol.sqlName + "->" + t.fkCol.fkTable : "";
            String fkAns = Ask.ask(t.sqlName + ".fk",
                    "  Khoa ngoai cua '" + t.sqlName + "' (dang cot->bang, de trong = khong co)", detected);
            if (fkAns.isBlank()) {
                t.fkCol = null;
                t.parent = null;
            } else {
                Matcher fm = Pattern.compile("([A-Za-z0-9_]+)\\s*->\\s*([A-Za-z0-9_]+)").matcher(fkAns);
                if (fm.find()) {
                    Col fc = t.byName(fm.group(1));
                    Tbl pt = schema.byName(fm.group(2));
                    if (fc == null) {
                        Out.warn("    Khong co cot '" + fm.group(1) + "' trong bang " + t.sqlName + " - bo qua.");
                    } else if (pt == null) {
                        Out.warn("    Khong co bang '" + fm.group(2) + "' - bo qua.");
                    } else {
                        fc.fkTable = pt.sqlName;
                        fc.fkColumn = pt.pk != null ? pt.pk.sqlName : "";
                        fc.javaType = "Long";
                        t.fkCol = fc;
                        t.parent = pt;
                    }
                } else {
                    Out.warn("    Khong doc duoc '" + fkAns + "', can dang cot->bang. Bo qua.");
                }
            }
            if (t.parent == null) continue;
            boolean cross = t.parent.svc != t.svc;
            String ans = Ask.ask(t.sqlName + ".cross",
                    "  " + t.entity + " co FK -> " + t.parent.entity
                            + (cross ? " (khac service, se goi Feign)" : " (cung service)") + ". Dung Feign",
                    cross ? "y" : "n");
            t.crossService = ans.equalsIgnoreCase("y");
            if (t.crossService && !cross) {
                Out.warn("    " + t.entity + " va " + t.parent.entity + " nam cung project '"
                        + t.svc.project + "' - khong the goi Feign sang chinh no. Chuyen ve khong dung Feign.");
                t.crossService = false;
            }
            if (!t.crossService) continue;

            t.svc.feignTargets.add(t.parent.svc);
            Out.hint("    Cach " + t.entity + "DTO the hien " + t.parent.entity + ":");
            Out.hint("      detail = 2 DTO: " + t.entity + "DTO co " + t.fkCol.javaName
                    + " phang, " + t.entity + "DetailDTO long object (GET dung ban long)");
            Out.hint("      nested = 1 DTO duy nhat, luon long object " + t.parent.entity + "DTO");
            Out.hint("      flat   = 1 DTO duy nhat, chi co " + t.fkCol.javaName);
            String dtoMode = Ask.ask(t.sqlName + ".dtoMode", "  Chon (detail/nested/flat)", "detail")
                    .trim().toLowerCase(Locale.ROOT);
            t.hasDetailDto = dtoMode.startsWith("d");
            t.nestedInMain = dtoMode.startsWith("n");

            if (t.parent.statusCol != null) {
                Out.hint("    Chi dien neu de bai NEU RO yeu cau nay (vd 'Room phai AVAILABLE'). Khong co trong de -> de trong.");
                String parentStatusAns = Ask.ask(t.sqlName + ".parentStatus",
                        "  Trang thai bat buoc cua " + t.parent.entity + " khi tao (de trong = khong yeu cau)", "");
                t.requiredParentStatus = parentStatusAns.isBlank() ? ""
                        : pickEnumValue(parentStatusAns, t.parent.statusCol.enumValues, "");
            }

            String defCmp = "";
            for (Col c : t.cols) {
                if (c.isInt() && t.parent.byName("capacity") != null) {
                    defCmp = c.sqlName + "<=capacity";
                    break;
                }
            }
            String cmp = Ask.ask(t.sqlName + ".parentCompare",
                    "  So sanh field con voi field cha (vd so_luong<=capacity, de trong neu khong co)", defCmp);
            if (!cmp.isBlank()) {
                Matcher mm = Pattern.compile("([A-Za-z0-9_]+)\\s*(<=|<|>=|>)\\s*([A-Za-z0-9_]+)").matcher(cmp);
                if (mm.find()) {
                    Col childC = t.byName(mm.group(1));
                    Col parC = t.parent.byName(mm.group(3));
                    if (childC != null && parC != null) {
                        t.parentCompare = new String[]{childC.javaName, mm.group(2), parC.javaName};
                    } else {
                        Out.warn("    Khong nhan dien duoc cot, bo qua.");
                    }
                }
            }

            for (Col c : t.cols) {
                if (!c.isDecimal()) continue;
                Out.hint("  Cot tien '" + c.javaName + "': 0=client gui, 1=<gia cha> x so ngay giua 2 cot date, 2=<gia cha> x 1 cot so");
                int mode = Ask.askInt(t.sqlName + "." + c.sqlName + ".compute", "    Chon cach tinh", 1);
                if (mode == 0) continue;
                c.computed = true;
                t.computedCol = c;
                t.computeMode = mode;
                String defPrice = t.parent.cols.stream().filter(Col::isDecimal).map(x -> x.javaName)
                        .findFirst().orElse("");
                String priceName = Ask.ask(t.sqlName + "." + c.sqlName + ".price",
                        "    Field gia ben " + t.parent.entity, defPrice);
                for (Col pc : t.parent.cols) if (pc.javaName.equalsIgnoreCase(priceName)) t.priceCol = pc;
                if (t.priceCol == null) {
                    Out.warn("    Khong tim thay field gia '" + priceName + "' ben " + t.parent.entity
                            + " -> de client tu gui gia tri cot nay.");
                    c.computed = false;
                    t.computedCol = null;
                    t.computeMode = 0;
                    break;
                }
                List<Col> dateCols = t.cols.stream().filter(Col::isDate).toList();
                if (mode == 1 && dateCols.size() < 2) {
                    Out.warn("    Bang chi co " + dateCols.size() + " cot ngay, khong tinh duoc so ngay"
                            + " -> de client tu gui gia tri cot nay.");
                    c.computed = false;
                    t.computedCol = null;
                    t.computeMode = 0;
                    break;
                }
                if (mode == 2) {
                    String defMul = t.cols.stream().filter(Col::isInt).map(x -> x.javaName).findFirst().orElse("");
                    String mul = Ask.ask(t.sqlName + "." + c.sqlName + ".multiplier", "    Nhan voi cot so nao", defMul);
                    for (Col mc : t.cols) if (mc.javaName.equalsIgnoreCase(mul)) t.multiplierCol = mc;
                }
                break;
            }
        }
        for (Tbl t : schema.tables) {
            if (t.usesDateUtils()) t.svc.needsDateUtils = true;
        }

        Out.section("6. BANG MA LOI (Response Behavior)");
        Out.hint("Enter = nhan gia tri mac dinh trong [ ]. Go ! de nhan het phan con lai.");
        s.statusSuccess = Ask.askInt("status.success", "  Api status khi thanh cong", 1);
        s.statusServerError = Ask.askInt("status.serverError", "  Api status khi loi he thong (HTTP 500)", 0);
        s.gValidation = new int[]{406, 2};
        s.gDuplicated = new int[]{226, 3};
        s.gNotFound = new int[]{404, 4};
        s.gNotAvailable = new int[]{400, 5};

        for (Tbl t : schema.tables) {
            Out.hint("--- " + t.basePath + " ---");
            askPair(s, t, "create", "POST   " + t.basePath, 201, s.statusSuccess);
            if (t.uniqueCol != null) askErr(s, t, "create", "duplicated", "  trung " + t.uniqueCol.javaName, s.gDuplicated);
            askErr(s, t, "create", "validation", "  validate loi", s.gValidation);
            if (t.crossService) {
                askErr(s, t, "create", "notFound", "  " + t.parent.entity + " khong ton tai", s.gNotFound);
                if (t.requiredParentStatus != null && !t.requiredParentStatus.isBlank()) {
                    askErr(s, t, "create", "notAvailable",
                            "  " + t.parent.entity + " khong o trang thai " + t.requiredParentStatus, s.gNotAvailable);
                }
            }
            askPair(s, t, "update", "PUT    " + t.basePath + "/{id}", 200, s.statusSuccess);
            askErr(s, t, "update", "validation", "  validate loi", s.gValidation);
            if (t.uniqueCol != null) askErr(s, t, "update", "duplicated", "  trung " + t.uniqueCol.javaName, s.gDuplicated);
            askErr(s, t, "update", "notFound", "  khong tim thay", s.gNotFound);

            askPair(s, t, "detail", "GET    " + t.basePath + "/{id}", 200, s.statusSuccess);
            askErr(s, t, "detail", "notFound", "  khong tim thay", s.gNotFound);

            askPair(s, t, "delete", "DELETE " + t.basePath + "/{id}", 200, s.statusSuccess);
            askErr(s, t, "delete", "notFound", "  khong tim thay", s.gNotFound);

            askPair(s, t, "list", "GET    " + t.basePath, 200, s.statusSuccess);
            askErr(s, t, "list", "validation", "  validate loi", s.gValidation);
        }

        s.gValidation = mostCommon(s, "validation", s.gValidation);
        s.gDuplicated = mostCommon(s, "duplicated", s.gDuplicated);
        s.gNotFound = mostCommon(s, "notFound", s.gNotFound);
        s.gNotAvailable = mostCommon(s, "notAvailable", s.gNotAvailable);
        return s;
    }

    /**
     * Neu de bai dung CUNG 1 ma loi cho toan bo endpoint (truong hop pho bien nhat),
     * gia tri do phai tro thanh hang so trong ApiError.java thay vi bi coi la
     * "khac mac dinh" va bi in thang (inline) o tung noi goi - nhu vay sua 1 cho
     * (ApiError.java) la du, dung nhu muc tieu cua thiet ke.
     */
    static int[] mostCommon(Spec s, String kind, int[] fallback) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        Map<String, int[]> value = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : s.codes.entrySet()) {
            if (!e.getKey().endsWith("." + kind)) continue;
            String key = e.getValue()[0] + "/" + e.getValue()[1];
            freq.merge(key, 1, Integer::sum);
            value.put(key, e.getValue());
        }
        if (freq.isEmpty()) return fallback;
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return value.get(best);
    }

    static void askPair(Spec s, Tbl t, String ep, String label, int defHttp, int defStatus) {
        int http = Ask.askInt(t.sqlName + "." + ep + ".ok.http", label + " - HTTP khi thanh cong", defHttp);
        int st = Ask.askInt(t.sqlName + "." + ep + ".ok.status", label + " - api status khi thanh cong", defStatus);
        s.codes.put(t.sqlName + "." + ep + ".ok", new int[]{http, st});
    }

    static void askErr(Spec s, Tbl t, String ep, String kind, String label, int[] def) {
        int http = Ask.askInt(t.sqlName + "." + ep + "." + kind + ".http", label + " - HTTP", def[0]);
        int st = Ask.askInt(t.sqlName + "." + ep + "." + kind + ".status", label + " - api status", def[1]);
        s.codes.put(t.sqlName + "." + ep + "." + kind, new int[]{http, st});
    }

    /**
     * Mini-DSL de khai BAT KY rang buoc nao ma parser khong tu doc duoc.
     * Nho vay khi de bai dung cu phap SQL la, khong can sua code Java.
     *
     *   ten_cot:enum=A,B,C      danh sach gia tri cho phep
     *   ten_cot:range=1..10     min..max (bo trong 1 ve neu chi co 1 phia)
     *   ten_cot:maxlen=50       do dai toi da
     *   ten_cot:regex=^[A-Z]+$  bat buoc khop regex
     *   ten_cot:required        bat buoc co gia tri
     *   ten_cot:optional        cho phep null
     *   ten_cot:unique          gia tri duy nhat
     *   colA>colB               colA phai lon hon colB (dung cho 2 cot ngay)
     */
    static boolean applyDirective(Tbl t, String raw) {
        String d = raw.trim();
        if (d.isEmpty()) return false;

        Matcher cmp = Pattern.compile("^([A-Za-z0-9_]+)\\s*(>=|<=|>|<)\\s*([A-Za-z0-9_]+)$").matcher(d);
        if (cmp.matches()) {
            Col a = t.byName(cmp.group(1)), b = t.byName(cmp.group(3));
            if (a == null || b == null) {
                Out.warn("    Khong tim thay cot trong '" + d + "'.");
                return false;
            }
            t.colCompare.add(new String[]{a.sqlName, cmp.group(2), b.sqlName});
            return true;
        }

        int colon = d.indexOf(':');
        if (colon <= 0) {
            Out.warn("    Khong hieu '" + d + "'. Xem lai cu phap.");
            return false;
        }
        Col c = t.byName(d.substring(0, colon).trim());
        if (c == null) {
            Out.warn("    Khong co cot '" + d.substring(0, colon).trim() + "' trong bang " + t.sqlName + ".");
            return false;
        }
        String rest = d.substring(colon + 1).trim();
        int eq = rest.indexOf('=');
        String verb = (eq < 0 ? rest : rest.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
        String arg = eq < 0 ? "" : rest.substring(eq + 1).trim();

        try {
            switch (verb) {
                case "enum" -> {
                    c.enumValues.clear();
                    c.allowedValues.clear();
                    for (String v : arg.split(",")) if (!v.isBlank()) c.enumValues.add(v.trim());
                    if (!c.enumValues.stream().allMatch(Names::isJavaIdent)) {
                        c.allowedValues = new ArrayList<>(c.enumValues);
                        c.enumValues.clear();
                        c.javaType = "String";
                    }
                }
                case "range" -> {
                    String[] pr = arg.split("\\.\\.", -1);
                    c.minInt = pr.length > 0 && !pr[0].isBlank() ? Integer.parseInt(pr[0].trim()) : null;
                    c.maxInt = pr.length > 1 && !pr[1].isBlank() ? Integer.parseInt(pr[1].trim()) : null;
                }
                case "maxlen" -> c.length = Integer.parseInt(arg);
                case "daterange" -> {
                    if (!c.isDate()) {
                        Out.warn("    '" + c.sqlName + "' khong phai cot ngay, bo qua dateRange.");
                        return false;
                    }
                    String[] pr = arg.split("\\.\\.", -1);
                    c.dateMin = c.dateMax = null;
                    c.dateMinDays = c.dateMaxDays = null;
                    if (pr.length > 0 && !pr[0].isBlank()) parseDateBound(c, pr[0].trim(), true);
                    if (pr.length > 1 && !pr[1].isBlank()) parseDateBound(c, pr[1].trim(), false);
                }
                case "regex" -> c.regex = arg;
                case "required" -> c.notNull = true;
                case "optional" -> c.notNull = false;
                case "unique" -> {
                    c.unique = true;
                    if (t.uniqueCol == null) t.uniqueCol = c;
                }
                default -> {
                    Out.warn("    Khong hieu lenh '" + verb + "'.");
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            Out.warn("    Gia tri so khong hop le trong '" + d + "'.");
            return false;
        }
        return true;
    }

    /**
     * Bao cho nguoi dung biet MOI constraint parser khong hieu, thay vi nuot im lang.
     * Sau do cho phep khai bo sung bat ky rang buoc nao bang mini-DSL.
     */
    static void reviewConstraints(Schema schema) {
        int total = 0;
        for (Tbl t : schema.tables) total += t.unparsedChecks.size();

        if (total > 0) {
            Out.warn("Co " + total + " rang buoc trong SQL parser KHONG doc duoc:");
            for (Tbl t : schema.tables) {
                for (String u : t.unparsedChecks) {
                    Out.hint("   [" + t.sqlName + "] " + u);
                }
            }
            Out.hint("Neu day la rang buoc can validate, khai lai bang cu phap ben duoi.");
        } else {
            Out.ok("Doc duoc toan bo CHECK constraint trong SQL.");
        }

        for (Tbl t : schema.tables) {
            if (t.pkGuessed) {
                Out.warn("Bang '" + t.sqlName + "' khong khai PRIMARY KEY/IDENTITY -> tam lay cot '"
                        + t.pk.sqlName + "' lam khoa chinh. Kiem tra lai neu khong dung.");
            }
            if (t.extraFkCount > 0) {
                Out.warn("Bang '" + t.sqlName + "' co " + (t.extraFkCount + 1) + " khoa ngoai."
                        + " Tool chi sinh Feign/DTO long cho khoa ngoai DAU TIEN ('" + t.fkCol.sqlName
                        + "'), cac khoa con lai chi la truong Long phang -> tu bo sung neu de yeu cau.");
            }
            if (t.extraUniqueCount > 0) {
                Out.warn("Bang '" + t.sqlName + "' co " + (t.extraUniqueCount + 1) + " cot UNIQUE."
                        + " Tool chi check trung cho '" + t.uniqueCol.sqlName + "' -> tu bo sung neu de yeu cau.");
            }
        }

        Out.hint("");
        Out.hint("Bo sung / sua rang buoc (bo trong de bo qua). Nhieu lenh cach nhau dau ';'");
        Out.hint("  cot:enum=A,B,C   cot:range=1..10   cot:maxlen=50   cot:regex=^[A-Z]+$");
        Out.hint("  cot:required     cot:optional      cot:unique      cotA>cotB");
        Out.hint("  cot:dateRange=01/01/2000..+360    (moc ngay: dd/MM/yyyy, +N/-N ngay, now)");
        for (Tbl t : schema.tables) {
            String extra = Ask.ask(t.sqlName + ".extraRules",
                    "  Rang buoc them cho bang '" + t.sqlName + "'", "");
            if (extra.isBlank()) continue;
            int ok = 0;
            for (String part : extra.split(";")) {
                if (applyDirective(t, part)) ok++;
            }
            Out.ok("    Da ap dung " + ok + " rang buoc cho " + t.sqlName + ".");
        }

        Out.hint("");
        Out.hint("Ket qua sau ra soat:");
        for (Tbl t : schema.tables) Out.detail(t.describe());
    }

    /**
     * Doc 1 ve cua dateRange. Chap nhan:
     *   dd/MM/yyyy hoac yyyy-MM-dd  -> ngay tuyet doi
     *   +N / -N                     -> lech N ngay so voi hom nay
     *   now / today                 -> hom nay
     */
    static void parseDateBound(Col c, String raw, boolean isMin) {
        String v = raw.trim();
        if (v.equalsIgnoreCase("now") || v.equalsIgnoreCase("today")) {
            if (isMin) c.dateMinDays = 0; else c.dateMaxDays = 0;
            return;
        }
        Matcher rel = Pattern.compile("^([+-])\\s*(\\d+)$").matcher(v);
        if (rel.matches()) {
            int days = Integer.parseInt(rel.group(2)) * ("-".equals(rel.group(1)) ? -1 : 1);
            if (isMin) c.dateMinDays = days; else c.dateMaxDays = days;
            return;
        }
        Matcher dmy = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").matcher(v);
        if (dmy.matches()) {
            String iso = dmy.group(3) + "-" + pad2(dmy.group(2)) + "-" + pad2(dmy.group(1));
            if (isMin) c.dateMin = iso; else c.dateMax = iso;
            return;
        }
        Matcher iso = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matcher(v);
        if (iso.matches()) {
            String val = iso.group(1) + "-" + pad2(iso.group(2)) + "-" + pad2(iso.group(3));
            if (isMin) c.dateMin = val; else c.dateMax = val;
            return;
        }
        Out.warn("    Khong doc duoc moc ngay '" + v + "' (can dd/MM/yyyy, yyyy-MM-dd, +N, -N hoac now).");
    }

    static String pad2(String s) {
        return s.length() == 1 ? "0" + s : s;
    }

    static String pickEnumValue(String value, List<String> allowed, String fallback) {
        String v = value == null ? "" : value.trim();
        for (String a : allowed) if (a.equalsIgnoreCase(v)) return a;
        if (!v.isEmpty()) {
            Out.warn("    '" + v + "' khong thuoc " + allowed + " -> dung '"
                    + (fallback.isEmpty() ? "(xoa han)" : fallback) + "'.");
        }
        return fallback;
    }

    /**
     * Ten class enum phai KHAC ten class entity va khac cac enum khac trong cung package,
     * neu khong 2 file .java se ghi de len nhau.
     */
    static String enumClassName(Tbl t, Col c) {
        String suffix = Names.pascal(c.sqlName);
        String entPrefix = Names.pascal(Names.singular(t.sqlName));
        String name = suffix.startsWith(entPrefix)
                ? t.entity + suffix.substring(entPrefix.length())
                : t.entity + suffix;
        if (name.isEmpty() || name.equals(t.entity)) {
            name = t.entity + Names.pascal(c.sqlName);
        }
        if (name.equals(t.entity)) {
            name = t.entity + "Value";
        }
        return name;
    }

    /** Doi ten neu trung voi class da co trong cung package, va bao cho nguoi dung biet. */
    static String uniqueClassName(Set<String> used, String wanted, String what) {
        String name = wanted;
        int i = 2;
        while (!used.add(name)) {
            name = wanted + i++;
        }
        if (!name.equals(wanted)) {
            Out.warn("  Ten class '" + wanted + "' (" + what + ") bi trung -> doi thanh '" + name + "'.");
        }
        return name;
    }
}

// ======================================================================= ask

class Ask {
    static final Map<String, String> replay = new LinkedHashMap<>();
    static final Map<String, String> answers = new LinkedHashMap<>();
    static final Scanner sc = new Scanner(System.in);
    static boolean acceptAll = false;

    static boolean looksLikeAnswerFile(Path p) {
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8);
            return s.startsWith("# dap-an") || s.contains("\nstudentId=") || s.startsWith("studentId=");
        } catch (Exception e) {
            return false;
        }
    }

    static void loadReplay(Path p) throws IOException {
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int i = l.indexOf('=');
            if (i <= 0) continue;
            replay.put(l.substring(0, i).trim(), l.substring(i + 1).trim());
        }
    }

    static String askRequired(String key, String prompt) {
        if (replay.containsKey(key)) {
            String v = replay.get(key).trim();
            if (!v.isEmpty()) {
                answers.put(key, v);
                return v;
            }
        }
        while (true) {
            System.out.print(prompt + ": ");
            System.out.flush();
            if (!sc.hasNextLine()) {
                Out.err("Het du lieu nhap (EOF) nhung '" + key + "' bat buoc phai co gia tri. Dung chuong trinh.");
                System.exit(1);
            }
            String line = sc.nextLine().trim();
            if (line.equals("!")) {
                Out.warn("  Cau nay BAT BUOC nhap, khong co gia tri mac dinh de '!' nhan thay."
                        + " Go gia tri that.");
                continue;
            }
            if (!line.isEmpty()) {
                answers.put(key, line);
                return line;
            }
            Out.warn("  Bat buoc nhap, khong duoc de trong.");
        }
    }

    static String ask(String key, String prompt, String def) {
        if (replay.containsKey(key)) {
            String v = replay.get(key);
            answers.put(key, v);
            return v;
        }
        if (acceptAll) {
            answers.put(key, def);
            return def;
        }
        System.out.print(prompt + " [" + def + "]: ");
        System.out.flush();
        String line = sc.hasNextLine() ? sc.nextLine().trim() : "";
        if (line.equals("!")) {
            acceptAll = true;
            Out.hint("  -> nhan mac dinh cho toan bo cau hoi con lai.");
            line = "";
        }
        String v = line.isEmpty() ? def : line;
        answers.put(key, v);
        return v;
    }

    static int askRequiredInt(String key, String prompt) {
        while (true) {
            String v = askRequired(key, prompt);
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                Out.warn("  Phai la so nguyen.");
                answers.remove(key);
                replay.remove(key);
            }
        }
    }

    static int askInt(String key, String prompt, int def) {
        while (true) {
            String v = ask(key, prompt, String.valueOf(def));
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                Out.warn("  Phai la so nguyen.");
                answers.remove(key);
                replay.remove(key);
            }
        }
    }

    static void saveAnswers(Path p) throws IOException {
        StringBuilder sb = new StringBuilder("# dap-an sinh tu Generator.java\n");
        sb.append("# Chay lai:  java Generator.java sql/*.txt dap-an.txt\n\n");
        answers.forEach((k, v) -> sb.append(k).append("=").append(v).append("\n"));
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
    }
}

// ===================================================================== names

class Names {
    static String pascal(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : s.split("[_\\s-]+")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    static String camel(String s) {
        String p = pascal(s);
        return p.isEmpty() ? p : Character.toLowerCase(p.charAt(0)) + p.substring(1);
    }

    static String cap(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Tu khoa Java + vai class java.lang hay bi trung, khong dung lam ten bien/class duoc. */
    static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "var", "record", "yield", "sealed", "permits",
            "String", "Integer", "Long", "Double", "Boolean", "Object", "Class", "Enum", "Number",
            "System", "Thread", "Exception", "Error", "Override", "Math", "Byte", "Short", "Character", "Float");

    /** Tra ve ten hop le; neu trung tu khoa thi them hau to. */
    static String safeIdent(String name, String suffix) {
        return RESERVED.contains(name) ? name + suffix : name;
    }

    static boolean isJavaIdent(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static String singular(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.endsWith("ies") && l.length() > 3) return l.substring(0, l.length() - 3) + "y";
        if (l.endsWith("ses") || l.endsWith("xes") || l.endsWith("zes") || l.endsWith("ches") || l.endsWith("shes"))
            return l.substring(0, l.length() - 2);
        if (l.endsWith("us") || l.endsWith("ss") || l.endsWith("is")) return l;
        if (l.endsWith("s") && l.length() > 2) return l.substring(0, l.length() - 1);
        return l;
    }

    static String plural(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.endsWith("y") && l.length() > 1 && "aeiou".indexOf(l.charAt(l.length() - 2)) < 0)
            return l.substring(0, l.length() - 1) + "ies";
        if (l.endsWith("s") || l.endsWith("x") || l.endsWith("z") || l.endsWith("ch") || l.endsWith("sh"))
            return l + "es";
        return l + "s";
    }
}

// ====================================================================== emit

class Emit {

    static String TZ = "GMT+7";
    static int PAGE_DEF = 10;
    static int PAGE_MAX = 100;

    static void generate(Spec s, Path root) throws IOException {
        TZ = s.timezone;
        PAGE_DEF = s.defaultPageSize;
        PAGE_MAX = s.maxPageSize;
        Out.section("SINH CODE");
        w(root.resolve("pom.xml"), rootPom(s));
        w(root.resolve(".idea/compiler.xml"), ideaCompiler());
        w(root.resolve(".idea/encodings.xml"), ideaEncodings());
        w(root.resolve(".idea/misc.xml"), ideaMisc());

        for (Svc svc : s.services) {
            Path base = root.resolve("services").resolve(svc.project);
            String jp = "src/main/java/" + svc.pkg.replace('.', '/');
            w(base.resolve("pom.xml"), servicePom(s, svc));
            w(base.resolve("src/main/resources/application.properties"), serviceProps(s, svc));
            w(base.resolve(jp + "/" + appClassName(svc) + ".java"), appClass(s, svc));

            w(base.resolve(jp + "/common/BusinessException.java"), businessException(svc.pkg));
            w(base.resolve(jp + "/common/ApiError.java"), apiError(s, svc.pkg));
            w(base.resolve(jp + "/common/ValidationUtils.java"), validationUtils(svc.pkg));
            if (svc.needsDateUtils) w(base.resolve(jp + "/common/DateUtils.java"), dateUtils(svc.pkg));

            w(base.resolve(jp + "/dto/ApiResponseDTO.java"), apiResponseDTO(svc.pkg, !svc.feignTargets.isEmpty()));
            w(base.resolve(jp + "/dto/PageDTO.java"), pageDTO(svc.pkg));
            w(base.resolve(jp + "/config/GlobalExceptionHandler.java"), globalExceptionHandler(s, svc.pkg));
            w(base.resolve(jp + "/config/OpenApiConfig.java"), openApiConfig(svc.pkg, svc.project));

            Set<String> mirrors = new LinkedHashSet<>();
            for (Tbl t : svc.tables) {
                for (Col c : t.cols) {
                    if (c.isEnum()) w(base.resolve(jp + "/entity/" + c.enumClass + ".java"), enumClass(svc.pkg, c));
                }
                w(base.resolve(jp + "/entity/" + t.entity + ".java"), entityClass(svc.pkg, t));
                w(base.resolve(jp + "/repository/" + t.entity + "Repository.java"), repoClass(svc.pkg, t));
                w(base.resolve(jp + "/dto/" + t.entity + "DTO.java"), entityDto(svc.pkg, t));
                if (t.crossService && t.hasDetailDto)
                    w(base.resolve(jp + "/dto/" + t.entity + "DetailDTO.java"), detailDto(svc.pkg, t));
                if (t.crossService && mirrors.add(t.parent.entity)) {
                    w(base.resolve(jp + "/dto/" + t.parent.entity + "DTO.java"), mirrorDto(svc.pkg, t.parent));
                    w(base.resolve(jp + "/service/client/" + t.parent.entity + "Client.java"), feignClient(svc.pkg, t));
                }
                w(base.resolve(jp + "/service/" + t.entity + "Service.java"), serviceInterface(svc.pkg, t));
                w(base.resolve(jp + "/service/impl/" + t.entity + "ServiceImpl.java"), serviceImpl(s, svc.pkg, t));
                w(base.resolve(jp + "/controller/" + t.controllerClass + ".java"), controller(s, svc.pkg, t));
            }
        }

        Path gw = root.resolve("infras").resolve(s.gatewayProject);
        String gpkg = "fu." + s.studentIdLower + ".gateway";
        String gjp = "src/main/java/" + gpkg.replace('.', '/');
        w(gw.resolve("pom.xml"), gatewayPom(s));
        w(gw.resolve("src/main/resources/application.properties"), gatewayProps(s));
        w(gw.resolve(gjp + "/" + s.gatewayProject + "Application.java"), gatewayApp(gpkg, s));
        w(gw.resolve(gjp + "/config/GatewayConfig.java"), gatewayConfig(gpkg));
        w(gw.resolve(gjp + "/config/OpenApiConfig.java"), openApiConfig(gpkg, s.gatewayProject));

        w(root.resolve("HUONG_DAN.md"), guide(s));
    }

    static void w(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        Out.file(p.toString());
    }

    static String appClassName(Svc svc) {
        return svc.project + "Application";
    }

    // ------------------------------------------------------------- pom / cfg

    static String rootPom(Spec s) {
        StringBuilder mods = new StringBuilder();
        for (Svc svc : s.services) mods.append("        <module>services/").append(svc.project).append("</module>\n");
        mods.append("        <module>infras/").append(s.gatewayProject).append("</module>\n");
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>{{BOOT}}</version>
                        <relativePath/>
                    </parent>

                    <groupId>fu.{{IDL}}</groupId>
                    <artifactId>{{IDL}}_parent_project</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <name>{{IDL}}_parent_project</name>

                    <modules>
                {{MODULES}}    </modules>

                    <properties>
                        <java.version>21</java.version>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                        <spring-cloud.version>{{CLOUD}}</spring-cloud.version>
                        <springdoc.version>{{SPRINGDOC}}</springdoc.version>
                    </properties>

                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.cloud</groupId>
                                <artifactId>spring-cloud-dependencies</artifactId>
                                <version>${spring-cloud.version}</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """
                .replace("{{BOOT}}", s.bootVersion)
                .replace("{{CLOUD}}", s.cloudVersion)
                .replace("{{SPRINGDOC}}", s.springdocVersion)
                .replace("{{IDL}}", s.studentIdLower)
                .replace("{{MODULES}}", mods.toString());
    }

    static String servicePom(Spec s, Svc svc) {
        String feign = svc.feignTargets.isEmpty() ? "" : """
                        <dependency>
                            <groupId>org.springframework.cloud</groupId>
                            <artifactId>spring-cloud-starter-openfeign</artifactId>
                        </dependency>
                """;
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>fu.{{IDL}}</groupId>
                        <artifactId>{{IDL}}_parent_project</artifactId>
                        <version>1.0.0</version>
                        <relativePath>../../pom.xml</relativePath>
                    </parent>

                    <artifactId>{{PROJECT}}</artifactId>
                    <packaging>jar</packaging>
                    <name>{{PROJECT}}</name>

                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-data-jpa</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-validation</artifactId>
                        </dependency>
                {{FEIGN}}        <dependency>
                            <groupId>com.microsoft.sqlserver</groupId>
                            <artifactId>mssql-jdbc</artifactId>
                            <scope>runtime</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.springdoc</groupId>
                            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                            <version>${springdoc.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <optional>true</optional>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-test</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <configuration>
                                    <excludes>
                                        <exclude>
                                            <groupId>org.projectlombok</groupId>
                                            <artifactId>lombok</artifactId>
                                        </exclude>
                                    </excludes>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
                .replace("{{IDL}}", s.studentIdLower)
                .replace("{{PROJECT}}", svc.project)
                .replace("{{FEIGN}}", feign);
    }

    static String serviceProps(Spec s, Svc svc) {
        StringBuilder urls = new StringBuilder();
        for (Svc target : svc.feignTargets) {
            urls.append(feignUrlKey(target)).append("=http://localhost:").append(target.port).append("\n");
        }
        return """
                spring.application.name={{PROJECT}}
                server.port={{PORT}}

                spring.datasource.url=jdbc:sqlserver://{{HOST}};databaseName={{DB}};encrypt=false;
                spring.datasource.username={{USER}}
                spring.datasource.password={{PASS}}
                spring.datasource.driver-class-name=com.microsoft.sqlserver.jdbc.SQLServerDriver

                spring.jpa.hibernate.ddl-auto=none

                {{URLS}}spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.SQLServerDialect
                spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
                spring.jpa.open-in-view=false
                spring.jpa.properties.hibernate.show_sql=true

                spring.jackson.serialization.write-dates-as-timestamps=false

                springdoc.api-docs.path=/v3/api-docs
                springdoc.swagger-ui.path=/swagger-ui.html
                """
                .replace("{{PROJECT}}", svc.project)
                .replace("{{PORT}}", String.valueOf(svc.port))
                .replace("{{HOST}}", s.dbHost)
                .replace("{{DB}}", s.dbName)
                .replace("{{USER}}", s.dbUser)
                .replace("{{PASS}}", s.dbPass)
                .replace("{{URLS}}", urls.isEmpty() ? "" : urls + "\n");
    }

    static String feignUrlKey(Svc target) {
        return Names.lower(target.tables.get(0).entity) + ".service.url";
    }

    static String appClass(Spec s, Svc svc) {
        boolean feign = !svc.feignTargets.isEmpty();
        StringBuilder imports = new StringBuilder();
        if (svc.needsDateUtils) {
            imports.append("import ").append(svc.pkg).append(".common.DateUtils;\n");
            imports.append("import jakarta.annotation.PostConstruct;\n");
        }
        imports.append("import org.springframework.boot.SpringApplication;\n");
        imports.append("import org.springframework.boot.autoconfigure.SpringBootApplication;\n");
        if (feign) imports.append("import org.springframework.cloud.openfeign.EnableFeignClients;\n");
        if (svc.needsDateUtils) imports.append("\nimport java.util.TimeZone;\n");

        String tz = svc.needsDateUtils ? """

                    @PostConstruct
                    public void initTimeZone() {
                        TimeZone.setDefault(TimeZone.getTimeZone(DateUtils.ZONE_ID));
                    }
                """ : "";

        return """
                package {{PKG}};

                {{IMPORTS}}
                @SpringBootApplication
                {{FEIGN}}public class {{CLASS}} {

                    public static void main(String[] args) {
                        SpringApplication.run({{CLASS}}.class, args);
                    }
                {{TZ}}}
                """
                .replace("{{PKG}}", svc.pkg)
                .replace("{{IMPORTS}}", imports.toString())
                .replace("{{FEIGN}}", feign ? "@EnableFeignClients\n" : "")
                .replace("{{CLASS}}", appClassName(svc))
                .replace("{{TZ}}", tz);
    }

    // --------------------------------------------------------------- common

    static String businessException(String pkg) {
        return """
                package {{PKG}}.common;

                public class BusinessException extends RuntimeException {

                    private final int apiStatus;
                    private final int httpStatus;

                    public BusinessException(int apiStatus, int httpStatus, String message) {
                        super(message);
                        this.apiStatus = apiStatus;
                        this.httpStatus = httpStatus;
                    }

                    public int getApiStatus() {
                        return apiStatus;
                    }

                    public int getHttpStatus() {
                        return httpStatus;
                    }
                }
                """.replace("{{PKG}}", pkg);
    }

    static String apiError(Spec s, String pkg) {
        return """
                package {{PKG}}.common;

                public final class ApiError {

                    private ApiError() {
                    }

                    public static final int STATUS_SUCCESS = {{OK}};
                    public static final int STATUS_VALIDATION = {{VS}};
                    public static final int STATUS_DUPLICATED = {{DS}};
                    public static final int STATUS_NOT_FOUND = {{NS}};
                    public static final int STATUS_NOT_AVAILABLE = {{AS}};
                    public static final int STATUS_SERVER_ERROR = {{ES}};

                    public static final int HTTP_VALIDATION = {{VH}};
                    public static final int HTTP_DUPLICATED = {{DH}};
                    public static final int HTTP_NOT_FOUND = {{NH}};
                    public static final int HTTP_NOT_AVAILABLE = {{AH}};

                    public static BusinessException validation(String message) {
                        return new BusinessException(STATUS_VALIDATION, HTTP_VALIDATION, message);
                    }

                    public static BusinessException duplicated(String message) {
                        return new BusinessException(STATUS_DUPLICATED, HTTP_DUPLICATED, message);
                    }

                    public static BusinessException notFound(String message) {
                        return new BusinessException(STATUS_NOT_FOUND, HTTP_NOT_FOUND, message);
                    }

                    public static BusinessException notAvailable(String message) {
                        return new BusinessException(STATUS_NOT_AVAILABLE, HTTP_NOT_AVAILABLE, message);
                    }
                }
                """
                .replace("{{PKG}}", pkg)
                .replace("{{OK}}", String.valueOf(s.statusSuccess))
                .replace("{{ES}}", String.valueOf(s.statusServerError))
                .replace("{{VH}}", String.valueOf(s.gValidation[0]))
                .replace("{{VS}}", String.valueOf(s.gValidation[1]))
                .replace("{{DH}}", String.valueOf(s.gDuplicated[0]))
                .replace("{{DS}}", String.valueOf(s.gDuplicated[1]))
                .replace("{{NH}}", String.valueOf(s.gNotFound[0]))
                .replace("{{NS}}", String.valueOf(s.gNotFound[1]))
                .replace("{{AH}}", String.valueOf(s.gNotAvailable[0]))
                .replace("{{AS}}", String.valueOf(s.gNotAvailable[1]));
    }

    static String validationUtils(String pkg) {
        return """
                package {{PKG}}.common;

                import java.math.BigDecimal;

                public final class ValidationUtils {

                    private ValidationUtils() {
                    }

                    public static void requireNotNull(Object value, String message) {
                        if (value == null) {
                            throw ApiError.validation(message);
                        }
                    }

                    public static void requireNotBlank(String value, String message) {
                        if (value == null || value.isBlank()) {
                            throw ApiError.validation(message);
                        }
                    }

                    public static void requireMaxLength(String value, int max, String message) {
                        if (value != null && value.length() > max) {
                            throw ApiError.validation(message);
                        }
                    }

                    public static void requireMatches(String value, String regex, String message) {
                        if (value != null && !value.matches(regex)) {
                            throw ApiError.validation(message);
                        }
                    }

                    public static void requireInRange(Integer value, int min, int max, String message) {
                        if (value == null || value < min || value > max) {
                            throw ApiError.validation(message);
                        }
                    }

                    public static void requireMin(Integer value, int min, String message) {
                        if (value == null || value < min) {
                            throw ApiError.validation(message);
                        }
                    }

                    public static void requireMax(Integer value, int max, String message) {
                        if (value == null || value > max) {
                            throw ApiError.validation(message);
                        }
                    }

                    public static void requireOneOf(String value, java.util.List<String> allowed, String message) {
                        if (value != null && !allowed.contains(value)) {
                            throw ApiError.validation(message);
                        }
                    }

                    public static void requirePositive(BigDecimal value, String message) {
                        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
                            throw ApiError.validation(message);
                        }
                    }

                    public static void requireNonNegative(BigDecimal value, String message) {
                        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
                            throw ApiError.validation(message);
                        }
                    }
                }
                """.replace("{{PKG}}", pkg);
    }

    static String dateUtils(String pkg) {
        return """
                package {{PKG}}.common;

                import java.time.Instant;
                import java.time.LocalDate;
                import java.time.ZoneId;
                import java.time.temporal.ChronoUnit;
                import java.util.Date;

                public final class DateUtils {

                    public static final String ZONE_ID = "{{TZ}}";

                    private static final ZoneId ZONE = ZoneId.of(ZONE_ID);

                    private DateUtils() {
                    }

                    public static LocalDate toLocalDate(Date date) {
                        return Instant.ofEpochMilli(date.getTime()).atZone(ZONE).toLocalDate();
                    }

                    public static long daysBetween(Date from, Date to) {
                        return ChronoUnit.DAYS.between(toLocalDate(from), toLocalDate(to));
                    }

                    public static boolean isAfterByDate(Date left, Date right) {
                        return toLocalDate(left).isAfter(toLocalDate(right));
                    }

                    public static Date of(int year, int month, int day) {
                        return Date.from(LocalDate.of(year, month, day).atStartOfDay(ZONE).toInstant());
                    }

                    public static Date plusDaysFromNow(int days) {
                        return Date.from(LocalDate.now(ZONE).plusDays(days).atStartOfDay(ZONE).toInstant());
                    }

                    public static boolean beforeByDate(Date value, Date bound) {
                        return toLocalDate(value).isBefore(toLocalDate(bound));
                    }

                    public static boolean afterByDate(Date value, Date bound) {
                        return toLocalDate(value).isAfter(toLocalDate(bound));
                    }
                }
                """.replace("{{PKG}}", pkg).replace("{{TZ}}", TZ);
    }

    // ------------------------------------------------------------------ dto

    static String apiResponseDTO(String pkg, boolean mutable) {
        if (!mutable) {
            return """
                    package {{PKG}}.dto;

                    import com.fasterxml.jackson.annotation.JsonInclude;

                    import java.time.Instant;
                    import java.time.temporal.ChronoUnit;

                    @JsonInclude(JsonInclude.Include.NON_NULL)
                    public class ApiResponseDTO {

                        private final int status;
                        private final String message;
                        private final Object data;
                        private final String timestamp;

                        public ApiResponseDTO(int status, String message, Object data) {
                            this.status = status;
                            this.message = message;
                            this.data = data;
                            this.timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
                        }

                        public int getStatus() {
                            return status;
                        }

                        public String getMessage() {
                            return message;
                        }

                        public Object getData() {
                            return data;
                        }

                        public String getTimestamp() {
                            return timestamp;
                        }
                    }
                    """.replace("{{PKG}}", pkg);
        }
        return """
                package {{PKG}}.dto;

                import com.fasterxml.jackson.annotation.JsonInclude;

                import java.time.Instant;
                import java.time.temporal.ChronoUnit;

                @JsonInclude(JsonInclude.Include.NON_NULL)
                public class ApiResponseDTO {

                    private int status;
                    private String message;
                    private Object data;
                    private String timestamp;

                    public ApiResponseDTO() {
                    }

                    public ApiResponseDTO(int status, String message, Object data) {
                        this.status = status;
                        this.message = message;
                        this.data = data;
                        this.timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
                    }

                    public int getStatus() {
                        return status;
                    }

                    public void setStatus(int status) {
                        this.status = status;
                    }

                    public String getMessage() {
                        return message;
                    }

                    public void setMessage(String message) {
                        this.message = message;
                    }

                    public Object getData() {
                        return data;
                    }

                    public void setData(Object data) {
                        this.data = data;
                    }

                    public String getTimestamp() {
                        return timestamp;
                    }

                    public void setTimestamp(String timestamp) {
                        this.timestamp = timestamp;
                    }
                }
                """.replace("{{PKG}}", pkg);
    }

    static String pageDTO(String pkg) {
        return """
                package {{PKG}}.dto;

                import lombok.Getter;
                import lombok.Setter;

                @Getter
                @Setter
                public class PageDTO {

                    private int size;
                    private int page;
                    private int totalPages;
                    private long totalElements;
                    private boolean first;
                    private boolean last;
                    private Object content;
                }
                """.replace("{{PKG}}", pkg);
    }

    static String enumClass(String pkg, Col c) {
        return """
                package {{PKG}}.entity;

                public enum {{NAME}} {
                {{VALUES}}}
                """
                .replace("{{PKG}}", pkg)
                .replace("{{NAME}}", c.enumClass)
                .replace("{{VALUES}}", c.enumValues.stream().map(v -> "    " + v)
                        .collect(Collectors.joining(",\n")) + "\n");
    }

    static String entityClass(String pkg, Tbl t) {
        Set<String> imports = new LinkedHashSet<>();
        imports.add("jakarta.persistence.Column");
        imports.add("jakarta.persistence.Entity");
        imports.add("jakarta.persistence.GeneratedValue");
        imports.add("jakarta.persistence.GenerationType");
        imports.add("jakarta.persistence.Id");
        imports.add("jakarta.persistence.Table");
        StringBuilder fields = new StringBuilder();
        boolean hasEnum = false, hasDate = false, hasDecimal = false;
        for (Col c : t.cols) {
            if (c == t.pk) {
                fields.append("""
                            @Id
                            @GeneratedValue(strategy = GenerationType.IDENTITY)
                            @Column(name = "{{COL}}")
                            private Long {{VAR}};

                        """.replace("{{COL}}", c.sqlName).replace("{{VAR}}", c.javaName));
                continue;
            }
            String type;
            StringBuilder ann = new StringBuilder();
            if (c.isEnum()) {
                hasEnum = true;
                type = c.enumClass;
                ann.append("    @Enumerated(EnumType.STRING)\n");
            } else if (c.isDate()) {
                hasDate = true;
                type = "Date";
                ann.append("    @Temporal(TemporalType.")
                        .append(c.sqlType.equals("DATE") ? "DATE" : "TIMESTAMP").append(")\n");
            } else {
                type = c.javaType;
                if (c.isDecimal()) hasDecimal = true;
            }
            ann.append("    @Column(name = \"").append(c.sqlName).append("\"");
            if (c.notNull) ann.append(", nullable = false");
            if (c.length > 0) ann.append(", length = ").append(c.length);
            if (c.precision > 0) ann.append(", precision = ").append(c.precision).append(", scale = ").append(c.scale);
            if (c.unique) ann.append(", unique = true");
            ann.append(")\n");
            fields.append(ann).append("    private ").append(type).append(" ").append(c.javaName).append(";\n\n");
        }
        if (hasEnum) {
            imports.add("jakarta.persistence.EnumType");
            imports.add("jakarta.persistence.Enumerated");
        }
        if (hasDate) {
            imports.add("jakarta.persistence.Temporal");
            imports.add("jakarta.persistence.TemporalType");
        }
        StringBuilder imp = new StringBuilder();
        imports.stream().sorted().forEach(i -> imp.append("import ").append(i).append(";\n"));
        imp.append("import lombok.Getter;\nimport lombok.Setter;\n");
        if (hasDecimal || hasDate) {
            imp.append("\n");
            if (hasDecimal) imp.append("import java.math.BigDecimal;\n");
            if (hasDate) imp.append("import java.util.Date;\n");
        }
        return """
                package {{PKG}}.entity;

                {{IMPORTS}}
                @Entity
                @Table(name = "{{TABLE}}")
                @Getter
                @Setter
                public class {{ENTITY}} {

                {{FIELDS}}}
                """
                .replace("{{PKG}}", pkg)
                .replace("{{IMPORTS}}", imp.toString())
                .replace("{{TABLE}}", t.sqlName)
                .replace("{{ENTITY}}", t.entity)
                .replace("{{FIELDS}}", fields.toString());
    }

    static String repoClass(String pkg, Tbl t) {
        String unique = t.uniqueCol == null ? "" :
                "\n    boolean existsBy" + Names.cap(t.uniqueCol.javaName) + "("
                        + t.uniqueCol.javaType + " " + t.uniqueCol.javaName + ");\n";
        return """
                package {{PKG}}.repository;

                import {{PKG}}.entity.{{ENTITY}};
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
                import org.springframework.stereotype.Repository;

                @Repository
                public interface {{ENTITY}}Repository
                        extends JpaRepository<{{ENTITY}}, Long>, JpaSpecificationExecutor<{{ENTITY}}> {
                {{UNIQUE}}}
                """
                .replace("{{PKG}}", pkg)
                .replace("{{ENTITY}}", t.entity)
                .replace("{{UNIQUE}}", unique);
    }

    static String datePattern(Col c) {
        if (c.jsonPattern != null && !c.jsonPattern.isBlank()) return c.jsonPattern;
        return "DATE".equals(c.sqlType) ? "dd/MM/yyyy" : "dd/MM/yyyy HH:mm:ss";
    }

    static String dtoFields(Tbl t, boolean detail) {
        boolean nested = detail || (t.crossService && t.nestedInMain);
        StringBuilder sb = new StringBuilder();
        for (Col c : t.cols) {
            if (c == t.fkCol && t.crossService) {
                if (nested) continue;
                sb.append("    private Long ").append(c.javaName).append(";\n");
                continue;
            }
            String type = c == t.pk ? "Long" : (c.isEnum() ? c.enumClass : c.javaType);
            if (c.isDate()) {
                sb.append("    @JsonFormat(pattern = \"").append(datePattern(c))
                        .append("\", timezone = \"").append(TZ).append("\")\n");
            }
            sb.append("    private ").append(type).append(" ").append(c.javaName).append(";\n");
            if (c.isDate()) sb.append("\n");
        }
        if (nested) {
            sb.append("    private ").append(t.parent.entity).append("DTO ")
                    .append(Names.camel(t.parent.entity)).append(";\n");
        }
        return sb.toString();
    }

    static String dtoImports(Tbl t, String pkg, boolean detail) {
        Set<String> imp = new LinkedHashSet<>();
        imp.add("com.fasterxml.jackson.annotation.JsonInclude");
        boolean hasDate = t.cols.stream().anyMatch(Col::isDate);
        if (hasDate) imp.add("com.fasterxml.jackson.annotation.JsonFormat");
        for (Col c : t.cols) if (c.isEnum()) imp.add(pkg + ".entity." + c.enumClass);
        StringBuilder sb = new StringBuilder();
        imp.stream().sorted().forEach(i -> sb.append("import ").append(i).append(";\n"));
        sb.append("import lombok.Getter;\nimport lombok.NoArgsConstructor;\nimport lombok.Setter;\n");
        boolean hasDec = t.cols.stream().anyMatch(Col::isDecimal);
        if (hasDec || hasDate) {
            sb.append("\n");
            if (hasDec) sb.append("import java.math.BigDecimal;\n");
            if (hasDate) sb.append("import java.util.Date;\n");
        }
        return sb.toString();
    }

    static String entityDto(String pkg, Tbl t) {
        return """
                package {{PKG}}.dto;

                {{IMPORTS}}
                @JsonInclude(JsonInclude.Include.NON_NULL)
                @Getter
                @Setter
                @NoArgsConstructor
                public class {{ENTITY}}DTO {

                {{FIELDS}}}
                """
                .replace("{{PKG}}", pkg)
                .replace("{{IMPORTS}}", dtoImports(t, pkg, false))
                .replace("{{ENTITY}}", t.entity)
                .replace("{{FIELDS}}", dtoFields(t, false));
    }

    static String detailDto(String pkg, Tbl t) {
        return """
                package {{PKG}}.dto;

                {{IMPORTS}}
                @JsonInclude(JsonInclude.Include.NON_NULL)
                @Getter
                @Setter
                @NoArgsConstructor
                public class {{ENTITY}}DetailDTO {

                {{FIELDS}}}
                """
                .replace("{{PKG}}", pkg)
                .replace("{{IMPORTS}}", dtoImports(t, pkg, true))
                .replace("{{ENTITY}}", t.entity)
                .replace("{{FIELDS}}", dtoFields(t, true));
    }

    static String mirrorDto(String pkg, Tbl parent) {
        StringBuilder fields = new StringBuilder();
        boolean hasDec = false, hasDate = false;
        for (Col c : parent.cols) {
            String type = c == parent.pk ? "Long" : (c.isEnum() ? "String" : c.javaType);
            if (c.isDecimal()) hasDec = true;
            if (c.isDate()) {
                hasDate = true;
                fields.append("    @JsonFormat(pattern = \"").append(datePattern(c))
                        .append("\", timezone = \"").append(TZ).append("\")\n");
            }
            fields.append("    private ").append(type).append(" ").append(c.javaName).append(";\n");
        }
        StringBuilder imp = new StringBuilder();
        if (hasDate) imp.append("import com.fasterxml.jackson.annotation.JsonFormat;\n");
        imp.append("import com.fasterxml.jackson.annotation.JsonIgnoreProperties;\n");
        imp.append("import com.fasterxml.jackson.annotation.JsonInclude;\n");
        imp.append("import lombok.Getter;\nimport lombok.NoArgsConstructor;\nimport lombok.Setter;\n");
        if (hasDec || hasDate) {
            imp.append("\n");
            if (hasDec) imp.append("import java.math.BigDecimal;\n");
            if (hasDate) imp.append("import java.util.Date;\n");
        }
        return """
                package {{PKG}}.dto;

                {{IMPORTS}}
                @JsonInclude(JsonInclude.Include.NON_NULL)
                @JsonIgnoreProperties(ignoreUnknown = true)
                @Getter
                @Setter
                @NoArgsConstructor
                public class {{ENTITY}}DTO {

                {{FIELDS}}}
                """
                .replace("{{PKG}}", pkg)
                .replace("{{IMPORTS}}", imp.toString())
                .replace("{{ENTITY}}", parent.entity)
                .replace("{{FIELDS}}", fields.toString());
    }

    static String feignClient(String pkg, Tbl t) {
        Tbl p = t.parent;
        return """
                package {{PKG}}.service.client;

                import {{PKG}}.dto.ApiResponseDTO;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;

                @FeignClient(name = "{{TARGET}}", url = "${{{URLKEY}}}")
                public interface {{PARENT}}Client {

                    @GetMapping("{{PATH}}/{{{PKVAR}}}")
                    ApiResponseDTO get{{PARENT}}Detail(@PathVariable("{{PKVAR}}") Long {{PKVAR}});
                }
                """
                .replace("{{PKG}}", pkg)
                .replace("{{TARGET}}", p.svc.project)
                .replace("{{URLKEY}}", feignUrlKey(p.svc))
                .replace("{{PARENT}}", p.entity)
                .replace("{{PATH}}", p.basePath)
                .replace("{{PKVAR}}", p.pk.javaName);
    }

    // -------------------------------------------------------------- service

    static String serviceInterface(String pkg, Tbl t) {
        boolean detail = t.crossService && t.hasDetailDto;
        String detailType = detail ? t.entity + "DetailDTO" : t.entity + "DTO";
        StringBuilder imp = new StringBuilder();
        imp.append("import ").append(pkg).append(".dto.PageDTO;\n");
        imp.append("import ").append(pkg).append(".dto.").append(t.entity).append("DTO;\n");
        if (detail) imp.append("import ").append(pkg).append(".dto.").append(t.entity).append("DetailDTO;\n");
        for (Col c : t.filters) if (c.isEnum()) imp.append("import ").append(pkg).append(".entity.").append(c.enumClass).append(";\n");

        String delete = t.deleteStatus == null || t.deleteStatus.isBlank()
                ? "    void delete(Long " + t.pk.javaName + ");"
                : "    void " + deleteMethod(t) + "(Long " + t.pk.javaName + ");";

        return """
                package {{PKG}}.service;

                {{IMPORTS}}
                public interface {{ENTITY}}Service {

                    {{ENTITY}}DTO create({{ENTITY}}DTO dto);

                    {{ENTITY}}DTO update(Long {{PKVAR}}, {{ENTITY}}DTO dto);

                    {{DETAIL}} getDetail(Long {{PKVAR}});

                {{DELETE}}

                    PageDTO getAll(Integer page, Integer size{{FILTERARGS}});
                }
                """
                .replace("{{PKG}}", pkg)
                .replace("{{IMPORTS}}", imp.toString())
                .replace("{{ENTITY}}", t.entity)
                .replace("{{DETAIL}}", detailType)
                .replace("{{PKVAR}}", t.pk.javaName)
                .replace("{{DELETE}}", delete)
                .replace("{{FILTERARGS}}", filterArgs(t));
    }

    static String deleteMethod(Tbl t) {
        if (t.deleteStatus == null || t.deleteStatus.isBlank()) return "delete";
        return "set" + Names.pascal(t.deleteStatus.toLowerCase(Locale.ROOT));
    }

    static String filterArgs(Tbl t) {
        StringBuilder sb = new StringBuilder();
        for (Col c : t.filters) {
            String type = c.isEnum() ? c.enumClass : c.javaType;
            sb.append(", ").append(type).append(" ").append(c.javaName);
        }
        return sb.toString();
    }

    static String filterParams(Tbl t) {
        StringBuilder sb = new StringBuilder();
        for (Col c : t.filters) sb.append(", ").append(c.javaName);
        return sb.toString();
    }

    static String serviceImpl(Spec s, String pkg, Tbl t) {
        boolean detail = t.crossService && t.hasDetailDto;
        boolean nested = t.crossService && t.nestedInMain;
        String E = t.entity;
        String v = t.var;
        String pkv = t.pk.javaName;

        Set<String> imp = new LinkedHashSet<>();
        imp.add(pkg + ".common.ApiError");
        if (needsBusinessImport(s, t)) imp.add(pkg + ".common.BusinessException");
        imp.add(pkg + ".common.ValidationUtils");
        imp.add(pkg + ".dto.PageDTO");
        imp.add(pkg + ".dto." + E + "DTO");
        if (detail) imp.add(pkg + ".dto." + E + "DetailDTO");
        imp.add(pkg + ".entity." + E);
        for (Col c : t.cols) if (c.isEnum()) imp.add(pkg + ".entity." + c.enumClass);
        imp.add(pkg + ".repository." + E + "Repository");
        imp.add(pkg + ".service." + E + "Service");
        if (t.crossService) {
            imp.add(pkg + ".dto.ApiResponseDTO");
            imp.add(pkg + ".dto." + t.parent.entity + "DTO");
            imp.add(pkg + ".service.client." + t.parent.entity + "Client");
            imp.add("com.fasterxml.jackson.databind.ObjectMapper");
            imp.add("feign.FeignException");
        }
        if (t.usesDateUtils()) imp.add(pkg + ".common.DateUtils");
        imp.add("jakarta.persistence.criteria.Predicate");
        imp.add("org.springframework.data.domain.Page");
        imp.add("org.springframework.data.domain.PageRequest");
        imp.add("org.springframework.data.domain.Pageable");
        imp.add("org.springframework.data.jpa.domain.Specification");
        imp.add("org.springframework.stereotype.Service");
        imp.add("org.springframework.transaction.annotation.Transactional");

        StringBuilder javaImp = new StringBuilder();
        boolean needBigDecimal = t.computedCol != null;
        boolean needDate = t.cols.stream().anyMatch(Col::isDate);
        javaImp.append("import java.util.ArrayList;\n");
        if (needBigDecimal) javaImp.insert(0, "import java.math.BigDecimal;\n");
        if (needDate) javaImp.append("import java.util.Date;\n");
        if (detail || nested) javaImp.append("import java.util.HashMap;\n");
        javaImp.append("import java.util.List;\n");
        if (detail || nested) javaImp.append("import java.util.Map;\n");

        StringBuilder impB = new StringBuilder();
        imp.stream().sorted().forEach(i -> impB.append("import ").append(i).append(";\n"));
        impB.append("\n").append(javaImp);

        StringBuilder body = new StringBuilder();
        body.append("    private static final int DEFAULT_PAGE_SIZE = 10;\n");
        body.append("    private static final int MAX_PAGE_SIZE = 100;\n");
        if (t.requiredParentStatus != null && !t.requiredParentStatus.isBlank()) {
            body.append("    private static final String REQUIRED_").append(t.parent.entity.toUpperCase(Locale.ROOT))
                    .append("_STATUS = \"").append(t.requiredParentStatus).append("\";\n");
        }
        body.append("\n");
        body.append("    private final ").append(E).append("Repository ").append(v).append("Repository;\n");
        if (t.crossService) {
            body.append("    private final ").append(t.parent.entity).append("Client ")
                    .append(Names.camel(t.parent.entity)).append("Client;\n");
            body.append("    private final ObjectMapper objectMapper;\n");
        }
        body.append("\n    public ").append(E).append("ServiceImpl(").append(E).append("Repository ").append(v).append("Repository");
        if (t.crossService) {
            body.append(",\n").append(" ".repeat(E.length() + 24)).append(t.parent.entity).append("Client ")
                    .append(Names.camel(t.parent.entity)).append("Client,");
            body.append("\n").append(" ".repeat(E.length() + 24)).append("ObjectMapper objectMapper");
        }
        body.append(") {\n");
        body.append("        this.").append(v).append("Repository = ").append(v).append("Repository;\n");
        if (t.crossService) {
            body.append("        this.").append(Names.camel(t.parent.entity)).append("Client = ")
                    .append(Names.camel(t.parent.entity)).append("Client;\n");
            body.append("        this.objectMapper = objectMapper;\n");
        }
        body.append("    }\n\n");

        body.append(createMethod(s, t));
        body.append(updateMethod(s, t));
        body.append(detailMethod(s, t, detail));
        body.append(deleteMethodBody(s, t));
        body.append(listMethod(s, t, detail));
        body.append(filterMethod(t));
        body.append(validationMethods(t));
        if (t.crossService) body.append(feignMethods(t));
        body.append(mapperMethods(t, detail));

        return """
                package {{PKG}}.service.impl;

                {{IMPORTS}}
                @Service
                public class {{ENTITY}}ServiceImpl implements {{ENTITY}}Service {

                {{BODY}}}
                """
                .replace("{{PKG}}", pkg)
                .replace("{{IMPORTS}}", impB.toString())
                .replace("{{ENTITY}}", E)
                .replace("{{BODY}}", body.toString());
    }

    static String thrower(Spec s, Tbl t, String ep, String kind, int[] global, String factory, String msg) {
        int[] c = s.codes.get(t.sqlName + "." + ep + "." + kind);
        if (c == null || (c[0] == global[0] && c[1] == global[1])) {
            return "ApiError." + factory + "(\"" + msg + "\")";
        }
        return "new BusinessException(" + c[1] + ", " + c[0] + ", \"" + msg + "\")";
    }

    static boolean needsBusinessImport(Spec s, Tbl t) {
        for (Map.Entry<String, int[]> e : s.codes.entrySet()) {
            if (!e.getKey().startsWith(t.sqlName + ".")) continue;
            if (e.getKey().endsWith(".ok")) continue;
            int[] v = e.getValue();
            int[] g = e.getKey().endsWith("validation") ? s.gValidation
                    : e.getKey().endsWith("duplicated") ? s.gDuplicated
                    : e.getKey().endsWith("notFound") ? s.gNotFound : s.gNotAvailable;
            if (v[0] != g[0] || v[1] != g[1]) return true;
        }
        return false;
    }

    /**
     * Bieu thuc lay id cua ban ghi cha tu DTO.
     * - che do nested: DTO khong co truong id phang, phai lay qua object long
     * - cac che do khac: doc thang truong id
     */
    static String parentIdExpr(Tbl t, String dtoVar) {
        if (t.crossService && t.nestedInMain) {
            String g = "get" + t.parent.entity + "()";
            return "(" + dtoVar + "." + g + " == null ? null : " + dtoVar + "." + g + ".get"
                    + Names.cap(t.parent.pk.javaName) + "())";
        }
        return dtoVar + "." + t.fkCol.getter() + "()";
    }

    static String createMethod(Spec s, Tbl t) {
        boolean nested = t.crossService && t.nestedInMain;
        String E = t.entity, v = t.var;
        StringBuilder b = new StringBuilder();
        b.append("    @Override\n    @Transactional\n");
        b.append("    public ").append(E).append("DTO create(").append(E).append("DTO dto) {\n");
        b.append("        validateForCreate(dto);\n");
        if (t.uniqueCol != null) {
            b.append("        if (").append(v).append("Repository.existsBy").append(Names.cap(t.uniqueCol.javaName))
                    .append("(dto.").append(t.uniqueCol.getter()).append("())) {\n");
            b.append("            throw ").append(thrower(s, t, "create", "duplicated", s.gDuplicated, "duplicated",
                    t.entity + " " + t.uniqueCol.javaName + " is duplicated")).append(";\n");
            b.append("        }\n");
        }
        if (t.crossService) {
            String P = t.parent.entity, pv = Names.camel(P);
            b.append("\n        ").append(P).append("DTO ").append(pv).append(" = fetch")
                    .append(P).append("OrThrow(").append(parentIdExpr(t, "dto")).append(");\n");
            if (t.requiredParentStatus != null && !t.requiredParentStatus.isBlank()) {
                b.append("        if (!REQUIRED_").append(P.toUpperCase(Locale.ROOT)).append("_STATUS.equals(")
                        .append(pv).append(".").append(t.parent.statusCol.getter()).append("())) {\n");
                b.append("            throw ").append(thrower(s, t, "create", "notAvailable", s.gNotAvailable, "notAvailable",
                        P + " is not " + t.requiredParentStatus + " for this operation")).append(";\n");
                b.append("        }\n");
            }
            if (t.parentCompare != null) {
                b.append("        require").append(Names.cap(t.parentCompare[0])).append("WithinParent(dto.")
                        .append("get").append(Names.cap(t.parentCompare[0])).append("(), ").append(pv).append(");\n");
            }
        }
        b.append("\n        ").append(E).append(" ").append(v).append(" = new ").append(E).append("();\n");
        b.append("        applyToEntity(dto, ").append(v).append(");\n");
        if (t.crossService && t.fkCol != null) {
            b.append("        ").append(v).append(".").append(t.fkCol.setter()).append("(")
                    .append(parentIdExpr(t, "dto")).append(");\n");
        }
        if (t.computedCol != null) {
            b.append("        ").append(v).append(".").append(t.computedCol.setter()).append("(")
                    .append(computeCall(t, "dto", Names.camel(t.parent.entity))).append(");\n");
        }
        if (t.statusCol != null) {
            b.append("        ").append(v).append(".").append(t.statusCol.setter()).append("(dto.")
                    .append(t.statusCol.getter()).append("() != null ? dto.").append(t.statusCol.getter())
                    .append("() : ").append(t.statusCol.enumClass).append(".").append(t.defaultStatus).append(");\n");
        }
        b.append("\n        return toDto(").append(v).append("Repository.save(").append(v).append(")")
                .append(nested ? ", " + Names.camel(t.parent.entity) : "").append(");\n");
        b.append("    }\n\n");
        return b.toString();
    }

    static String computeCall(Tbl t, String dtoVar, String parentVar) {
        String price = parentVar + "." + t.priceCol.getter() + "()";
        if (t.computeMode == 1) {
            List<Col> dates = t.cols.stream().filter(Col::isDate).toList();
            String from = dates.size() > 0 ? dtoVar + "." + dates.get(0).getter() + "()" : "null";
            String to = dates.size() > 1 ? dtoVar + "." + dates.get(1).getter() + "()" : "null";
            return "computeAmount(" + price + ", " + from + ", " + to + ")";
        }
        String mul = t.multiplierCol != null ? dtoVar + "." + t.multiplierCol.getter() + "()" : "1";
        return "computeAmount(" + price + ", " + mul + ")";
    }

    static String updateMethod(Spec s, Tbl t) {
        boolean nested = t.crossService && t.nestedInMain;
        String E = t.entity, v = t.var, pkv = t.pk.javaName;
        StringBuilder b = new StringBuilder();
        b.append("    @Override\n    @Transactional\n");
        b.append("    public ").append(E).append("DTO update(Long ").append(pkv).append(", ").append(E).append("DTO dto) {\n");
        b.append("        ").append(E).append(" ").append(v).append(" = ").append(v).append("Repository.findById(").append(pkv).append(")\n");
        b.append("                .orElseThrow(() -> ").append(thrower(s, t, "update", "notFound", s.gNotFound, "notFound",
                E + " is not found")).append(");\n\n");
        b.append("        validateForUpdate(dto);\n");
        if (t.uniqueCol != null) {
            Col u = t.uniqueCol;
            b.append("        if (dto.").append(u.getter()).append("() != null && !dto.").append(u.getter())
                    .append("().equals(").append(v).append(".").append(u.getter()).append("())\n");
            b.append("                && ").append(v).append("Repository.existsBy").append(Names.cap(u.javaName))
                    .append("(dto.").append(u.getter()).append("())) {\n");
            b.append("            throw ").append(thrower(s, t, "update", "duplicated", s.gDuplicated, "duplicated",
                    E + " " + u.javaName + " is duplicated")).append(";\n");
            b.append("        }\n");
        }

        List<Col> dates = t.cols.stream().filter(Col::isDate).toList();
        List<String[]> dateRules = t.dateCompares();
        boolean hasDateRule = !dateRules.isEmpty();
        if (hasDateRule) {
            for (Col d : dates) {
                b.append("        Date ").append(d.javaName).append(" = dto.").append(d.getter())
                        .append("() != null ? dto.").append(d.getter()).append("() : ").append(v).append(".")
                        .append(d.getter()).append("();\n");
            }
            for (String[] cc : dateRules) {
                Col l = t.byName(cc[0]), r = t.byName(cc[2]);
                if (l == null || r == null) continue;
                b.append("        requireDateOrder(").append(l.javaName).append(", ").append(r.javaName).append(");\n");
            }
        }
        if (t.crossService) {
            String P = t.parent.entity, pv = Names.camel(P);
            b.append("        Long effective").append(Names.cap(t.fkCol.javaName)).append(" = ")
                    .append(parentIdExpr(t, "dto")).append(" != null ? ").append(parentIdExpr(t, "dto"))
                    .append(" : ").append(v).append(".").append(t.fkCol.getter()).append("();\n");
            b.append("        ").append(P).append("DTO ").append(pv).append(" = fetch").append(P)
                    .append("OrThrow(effective").append(Names.cap(t.fkCol.javaName)).append(");\n");
            if (t.parentCompare != null) {
                String eff = "effective" + Names.cap(t.parentCompare[0]);
                b.append("        Integer ").append(eff).append(" = dto.get").append(Names.cap(t.parentCompare[0]))
                        .append("() != null ? dto.get").append(Names.cap(t.parentCompare[0]))
                        .append("() : ").append(v).append(".get").append(Names.cap(t.parentCompare[0])).append("();\n");
                b.append("        require").append(Names.cap(t.parentCompare[0])).append("WithinParent(")
                        .append(eff).append(", ").append(pv).append(");\n");
            }
        }
        b.append("\n        applyToEntity(dto, ").append(v).append(");\n");
        if (hasDateRule) {
            for (Col d : dates) {
                b.append("        ").append(v).append(".").append(d.setter()).append("(").append(d.javaName).append(");\n");
            }
        }
        if (t.crossService && t.fkCol != null) {
            b.append("        ").append(v).append(".").append(t.fkCol.setter()).append("(effective")
                    .append(Names.cap(t.fkCol.javaName)).append(");\n");
        }
        if (t.statusCol != null) {
            b.append("        if (dto.").append(t.statusCol.getter()).append("() != null) {\n");
            b.append("            ").append(v).append(".").append(t.statusCol.setter()).append("(dto.")
                    .append(t.statusCol.getter()).append("());\n        }\n");
        }
        if (t.computedCol != null) {
            b.append("        ").append(v).append(".").append(t.computedCol.setter()).append("(")
                    .append(computeCall(t, v, Names.camel(t.parent.entity))).append(");\n");
        }
        b.append("\n        return toDto(").append(v).append("Repository.save(").append(v).append(")")
                .append(nested ? ", " + Names.camel(t.parent.entity) : "").append(");\n");
        b.append("    }\n\n");
        return b.toString();
    }

    static String toSql(Tbl t, String javaName) {
        for (Col c : t.cols) if (c.javaName.equals(javaName)) return c.sqlName;
        return javaName;
    }

    static String detailMethod(Spec s, Tbl t, boolean detail) {
        boolean nested = t.crossService && t.nestedInMain;
        String E = t.entity, v = t.var, pkv = t.pk.javaName;
        String ret = detail ? E + "DetailDTO" : E + "DTO";
        StringBuilder b = new StringBuilder();
        b.append("    @Override\n    @Transactional(readOnly = true)\n");
        b.append("    public ").append(ret).append(" getDetail(Long ").append(pkv).append(") {\n");
        b.append("        ").append(E).append(" ").append(v).append(" = ").append(v).append("Repository.findById(").append(pkv).append(")\n");
        b.append("                .orElseThrow(() -> ").append(thrower(s, t, "detail", "notFound", s.gNotFound, "notFound",
                E + " is not found")).append(");\n");
        if (detail) {
            b.append("        return toDetailDto(").append(v).append(", fetch").append(t.parent.entity)
                    .append("OrNull(").append(v).append(".").append(t.fkCol.getter()).append("()));\n");
        } else {
            if (nested) {
                b.append("        return toDto(").append(v).append(", fetch").append(t.parent.entity)
                        .append("OrNull(").append(v).append(".").append(t.fkCol.getter()).append("()));\n");
            } else {
                b.append("        return toDto(").append(v).append(");\n");
            }
        }
        b.append("    }\n\n");
        return b.toString();
    }

    static String deleteMethodBody(Spec s, Tbl t) {
        String E = t.entity, v = t.var, pkv = t.pk.javaName;
        StringBuilder b = new StringBuilder();
        b.append("    @Override\n    @Transactional\n");
        b.append("    public void ").append(deleteMethod(t)).append("(Long ").append(pkv).append(") {\n");
        b.append("        ").append(E).append(" ").append(v).append(" = ").append(v).append("Repository.findById(").append(pkv).append(")\n");
        b.append("                .orElseThrow(() -> ").append(thrower(s, t, "delete", "notFound", s.gNotFound, "notFound",
                E + " is not found")).append(");\n");
        if (t.deleteStatus == null || t.deleteStatus.isBlank()) {
            b.append("        ").append(v).append("Repository.delete(").append(v).append(");\n");
        } else {
            b.append("        ").append(v).append(".").append(t.statusCol.setter()).append("(")
                    .append(t.statusCol.enumClass).append(".").append(t.deleteStatus).append(");\n");
            b.append("        ").append(v).append("Repository.save(").append(v).append(");\n");
        }
        b.append("    }\n\n");
        return b.toString();
    }

    static String listMethod(Spec s, Tbl t, boolean detail) {
        String E = t.entity, v = t.var;
        StringBuilder b = new StringBuilder();
        b.append("    @Override\n    @Transactional(readOnly = true)\n");
        b.append("    public PageDTO getAll(Integer page, Integer size").append(filterArgs(t)).append(") {\n");
        b.append("        int pageNumber = (page == null || page < 0) ? 0 : page;\n");
        b.append("        int pageSize = (size == null || size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);\n\n");
        b.append("        Pageable pageable = PageRequest.of(pageNumber, pageSize);\n");
        b.append("        Page<").append(E).append("> result = ").append(v).append("Repository.findAll(buildFilter(")
                .append(filterParams(t).replaceFirst("^, ", "")).append("), pageable);\n\n");
        if (detail) {
            String P = t.parent.entity;
            b.append("        Map<Long, ").append(P).append("DTO> cache = new HashMap<>();\n");
            b.append("        List<").append(E).append("DetailDTO> content = new ArrayList<>();\n");
            b.append("        for (").append(E).append(" item : result.getContent()) {\n");
            b.append("            ").append(P).append("DTO parent = cache.computeIfAbsent(item.")
                    .append(t.fkCol.getter()).append("(), this::fetch").append(P).append("OrNull);\n");
            b.append("            content.add(toDetailDto(item, parent));\n        }\n\n");
        } else if (t.crossService && t.nestedInMain) {
            String P = t.parent.entity;
            b.append("        Map<Long, ").append(P).append("DTO> cache = new HashMap<>();\n");
            b.append("        List<").append(E).append("DTO> content = new ArrayList<>();\n");
            b.append("        for (").append(E).append(" item : result.getContent()) {\n");
            b.append("            ").append(P).append("DTO parent = cache.computeIfAbsent(item.")
                    .append(t.fkCol.getter()).append("(), this::fetch").append(P).append("OrNull);\n");
            b.append("            content.add(toDto(item, parent));\n        }\n\n");
        } else {
            b.append("        List<").append(E).append("DTO> content = result.getContent().stream().map(this::toDto).toList();\n\n");
        }
        b.append("        PageDTO pageDTO = new PageDTO();\n");
        b.append("        pageDTO.setPage(pageNumber);\n");
        b.append("        pageDTO.setSize(pageSize);\n");
        b.append("        pageDTO.setTotalPages(result.getTotalPages());\n");
        b.append("        pageDTO.setTotalElements(result.getTotalElements());\n");
        b.append("        pageDTO.setFirst(result.isFirst());\n");
        b.append("        pageDTO.setLast(result.isLast());\n");
        b.append("        pageDTO.setContent(content);\n");
        b.append("        return pageDTO;\n    }\n\n");
        return b.toString();
    }

    static String filterMethod(Tbl t) {
        StringBuilder b = new StringBuilder();
        b.append("    private Specification<").append(t.entity).append("> buildFilter(")
                .append(filterArgs(t).replaceFirst("^, ", "")).append(") {\n");
        b.append("        return (root, query, cb) -> {\n");
        b.append("            List<Predicate> predicates = new ArrayList<>();\n");
        for (Col c : t.filters) {
            if (t.partialFilters.contains(c.javaName) && c.isString()) {
                b.append("            if (").append(c.javaName).append(" != null && !").append(c.javaName).append(".isBlank()) {\n");
                b.append("                predicates.add(cb.like(cb.lower(root.get(\"").append(c.javaName)
                        .append("\")), \"%\" + ").append(c.javaName).append(".toLowerCase() + \"%\"));\n            }\n");
            } else if (c.isString()) {
                b.append("            if (").append(c.javaName).append(" != null && !").append(c.javaName).append(".isBlank()) {\n");
                b.append("                predicates.add(cb.equal(root.get(\"").append(c.javaName).append("\"), ")
                        .append(c.javaName).append("));\n            }\n");
            } else {
                b.append("            if (").append(c.javaName).append(" != null) {\n");
                b.append("                predicates.add(cb.equal(root.get(\"").append(c.javaName).append("\"), ")
                        .append(c.javaName).append("));\n            }\n");
            }
        }
        b.append("            return cb.and(predicates.toArray(new Predicate[0]));\n        };\n    }\n\n");
        return b.toString();
    }

    /** Sinh bieu thuc Java cho mot moc ngay: ngay tuyet doi hoac lech N ngay so voi hom nay. */
    static String dateBoundExpr(String absolute, Integer relativeDays) {
        if (absolute != null) {
            String[] p = absolute.split("-");
            return "DateUtils.of(" + Integer.parseInt(p[0]) + ", " + Integer.parseInt(p[1])
                    + ", " + Integer.parseInt(p[2]) + ")";
        }
        return "DateUtils.plusDaysFromNow(" + relativeDays + ")";
    }

    static String boundLabel(String absolute, Integer relativeDays) {
        if (absolute != null) {
            String[] p = absolute.split("-");
            return p[2] + "/" + p[1] + "/" + p[0];
        }
        if (relativeDays == 0) return "today";
        return "today " + (relativeDays > 0 ? "+" : "-") + Math.abs(relativeDays) + " days";
    }

    static String validationMethods(Tbl t) {
        boolean nested = t.crossService && t.nestedInMain;
        StringBuilder b = new StringBuilder();
        b.append("    private void validateForCreate(").append(t.entity).append("DTO dto) {\n");
        b.append("        ValidationUtils.requireNotNull(dto, \"Request body is required\");\n");
        for (Col c : t.cols) {
            if (c == t.pk || c == t.statusCol || c.computed) continue;
            if (nested && c == t.fkCol) continue;
            b.append(checks(c, "        ", false));
        }
        List<Col> dates = t.cols.stream().filter(Col::isDate).toList();
        if (!t.dateCompares().isEmpty()) {
            for (String[] cc : t.dateCompares()) {
                Col l = t.byName(cc[0]), r = t.byName(cc[2]);
                if (l == null || r == null) continue;
                b.append("        requireDateOrder(dto.").append(l.getter()).append("(), dto.")
                        .append(r.getter()).append("());\n");
            }
        }
        b.append("    }\n\n");

        b.append("    private void validateForUpdate(").append(t.entity).append("DTO dto) {\n");
        b.append("        ValidationUtils.requireNotNull(dto, \"Request body is required\");\n");
        for (Col c : t.cols) {
            if (c == t.pk || c == t.statusCol || c.computed) continue;
            if (nested && c == t.fkCol) continue;
            String inner = checks(c, "            ", true);
            if (inner.isEmpty()) continue;
            b.append("        if (dto.").append(c.getter()).append("() != null) {\n").append(inner).append("        }\n");
        }
        b.append("    }\n\n");

        for (Col c : t.cols) {
            if (!c.isDate()) continue;
            if (c.dateMin == null && c.dateMax == null && c.dateMinDays == null && c.dateMaxDays == null) continue;
            b.append("    private void require").append(Names.cap(c.javaName)).append("InRange(Date value) {\n");
            b.append("        if (value == null) {\n            return;\n        }\n");
            if (c.dateMin != null || c.dateMinDays != null) {
                b.append("        Date min = ").append(dateBoundExpr(c.dateMin, c.dateMinDays)).append(";\n");
                b.append("        if (DateUtils.beforeByDate(value, min)) {\n");
                b.append("            throw ApiError.validation(\"").append(Names.cap(c.javaName))
                        .append(" must be on or after ").append(boundLabel(c.dateMin, c.dateMinDays)).append("\");\n");
                b.append("        }\n");
            }
            if (c.dateMax != null || c.dateMaxDays != null) {
                b.append("        Date max = ").append(dateBoundExpr(c.dateMax, c.dateMaxDays)).append(";\n");
                b.append("        if (DateUtils.afterByDate(value, max)) {\n");
                b.append("            throw ApiError.validation(\"").append(Names.cap(c.javaName))
                        .append(" must be on or before ").append(boundLabel(c.dateMax, c.dateMaxDays)).append("\");\n");
                b.append("        }\n");
            }
            b.append("    }\n\n");
        }

        if (!t.dateCompares().isEmpty()) {
            String[] cc = t.dateCompares().get(0);
            Col l = t.byName(cc[0]), r = t.byName(cc[2]);
            b.append("    private void requireDateOrder(Date ").append(l.javaName).append(", Date ")
                    .append(r.javaName).append(") {\n");
            b.append("        if (").append(l.javaName).append(" == null || ").append(r.javaName).append(" == null) {\n");
            b.append("            return;\n        }\n");
            b.append("        if (!DateUtils.isAfterByDate(").append(l.javaName).append(", ").append(r.javaName).append(")) {\n");
            b.append("            throw ApiError.validation(\"").append(Names.cap(l.javaName))
                    .append(" must be after ").append(Names.cap(r.javaName)).append("\");\n        }\n    }\n\n");
        }
        if (t.parentCompare != null) {
            String cf = t.parentCompare[0], op = t.parentCompare[1], pf = t.parentCompare[2];
            String cmp = switch (op) {
                case "<=" -> ">";
                case "<" -> ">=";
                case ">=" -> "<";
                default -> "<=";
            };
            b.append("    private void require").append(Names.cap(cf)).append("WithinParent(Integer ")
                    .append(cf).append(", ").append(t.parent.entity).append("DTO parent) {\n");
            b.append("        if (").append(cf).append(" != null && parent.get").append(Names.cap(pf))
                    .append("() != null && ").append(cf).append(" ").append(cmp).append(" parent.get")
                    .append(Names.cap(pf)).append("()) {\n");
            b.append("            throw ApiError.validation(\"").append(Names.cap(cf))
                    .append(" must not exceed ").append(t.parent.entity).append(" ").append(pf).append("\");\n");
            b.append("        }\n    }\n\n");
        }
        if (t.computedCol != null) {
            if (t.computeMode == 1) {
                b.append("    private BigDecimal computeAmount(BigDecimal price, Date from, Date to) {\n");
                b.append("        if (price == null || from == null || to == null) {\n");
                b.append("            return BigDecimal.ZERO;\n        }\n");
                b.append("        long units = DateUtils.daysBetween(from, to);\n");
                b.append("        return price.multiply(BigDecimal.valueOf(units));\n    }\n\n");
            } else {
                b.append("    private BigDecimal computeAmount(BigDecimal price, Integer quantity) {\n");
                b.append("        if (price == null || quantity == null) {\n");
                b.append("            return BigDecimal.ZERO;\n        }\n");
                b.append("        return price.multiply(BigDecimal.valueOf(quantity));\n    }\n\n");
            }
        }
        return b.toString();
    }

    static String escapeJava(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String checks(Col c, String ind, boolean update) {
        StringBuilder b = new StringBuilder();
        String g = "dto." + c.getter() + "()";
        if (c.fkTable != null) {
            if (!update) b.append(ind).append("ValidationUtils.requireNotNull(").append(g).append(", \"")
                    .append(Names.cap(c.javaName)).append(" is required\");\n");
            return b.toString();
        }
        if (c.isEnum()) {
            if (!update) b.append(ind).append("ValidationUtils.requireNotNull(").append(g).append(", \"")
                    .append(Names.cap(c.javaName)).append(" is required\");\n");
            return b.toString();
        }
        if (c.isString()) {
            if (c.notNull || update)
                b.append(ind).append("ValidationUtils.requireNotBlank(").append(g).append(", \"")
                        .append(Names.cap(c.javaName)).append(update ? " must not be blank" : " is required").append("\");\n");
            if (c.length > 0)
                b.append(ind).append("ValidationUtils.requireMaxLength(").append(g).append(", ").append(c.length)
                        .append(", \"").append(Names.cap(c.javaName)).append(" must not exceed ")
                        .append(c.length).append(" characters\");\n");
            if (!c.allowedValues.isEmpty())
                b.append(ind).append("ValidationUtils.requireOneOf(").append(g).append(", java.util.List.of(")
                        .append(c.allowedValues.stream().map(x -> "\"" + x + "\"").collect(Collectors.joining(", ")))
                        .append("), \"").append(Names.cap(c.javaName)).append(" must be one of ")
                        .append(String.join(", ", c.allowedValues)).append("\");\n");
            if (c.regex != null && !c.regex.isBlank())
                b.append(ind).append("ValidationUtils.requireMatches(").append(g).append(", \"")
                        .append(escapeJava(c.regex)).append("\", \"").append(Names.cap(c.javaName))
                        .append(" has invalid format\");\n");
            return b.toString();
        }
        if (c.isDecimal()) {
            if (c.positive)
                b.append(ind).append("ValidationUtils.requirePositive(").append(g).append(", \"")
                        .append(Names.cap(c.javaName)).append(" must be greater than 0\");\n");
            else if (c.nonNegative)
                b.append(ind).append("ValidationUtils.requireNonNegative(").append(g).append(", \"")
                        .append(Names.cap(c.javaName)).append(" must be greater than or equal to 0\");\n");
            else if (c.notNull || update)
                b.append(ind).append("ValidationUtils.requireNotNull(").append(g).append(", \"")
                        .append(Names.cap(c.javaName)).append(" is required\");\n");
            return b.toString();
        }
        if (c.isInt()) {
            if (c.minInt != null && c.maxInt != null)
                b.append(ind).append("ValidationUtils.requireInRange(").append(g).append(", ").append(c.minInt)
                        .append(", ").append(c.maxInt).append(", \"").append(Names.cap(c.javaName))
                        .append(" must be between ").append(c.minInt).append(" and ").append(c.maxInt).append("\");\n");
            else if (c.minInt != null)
                b.append(ind).append("ValidationUtils.requireMin(").append(g).append(", ").append(c.minInt)
                        .append(", \"").append(Names.cap(c.javaName)).append(" must be greater than or equal to ")
                        .append(c.minInt).append("\");\n");
            else if (c.maxInt != null)
                b.append(ind).append("ValidationUtils.requireMax(").append(g).append(", ").append(c.maxInt)
                        .append(", \"").append(Names.cap(c.javaName)).append(" must be less than or equal to ")
                        .append(c.maxInt).append("\");\n");
            else if (c.notNull || update)
                b.append(ind).append("ValidationUtils.requireNotNull(").append(g).append(", \"")
                        .append(Names.cap(c.javaName)).append(" is required\");\n");
            return b.toString();
        }
        if (c.isDate()) {
            if (!update && c.notNull) {
                b.append(ind).append("ValidationUtils.requireNotNull(").append(g).append(", \"")
                        .append(Names.cap(c.javaName)).append(" is required\");\n");
            }
            if (c.dateMin != null || c.dateMaxDays != null || c.dateMax != null) {
                b.append(ind).append("require").append(Names.cap(c.javaName)).append("InRange(")
                        .append(g).append(");\n");
            }
            return b.toString();
        }
        // Cac kieu con lai (Boolean tu BIT, Double tu FLOAT...): van phai chan null neu NOT NULL,
        // khong thi se insert null -> loi SQL -> tra 500 thay vi loi validate.
        if (c.notNull || update) {
            b.append(ind).append("ValidationUtils.requireNotNull(").append(g).append(", \"")
                    .append(Names.cap(c.javaName)).append(" is required\");\n");
        }
        return b.toString();
    }

    static String feignMethods(Tbl t) {
        String P = t.parent.entity, pv = Names.camel(P);
        return """
                    private {{P}}DTO fetch{{P}}OrThrow(Long id) {
                        {{P}}DTO {{PV}} = fetch{{P}}OrNull(id);
                        if ({{PV}} == null) {
                            throw ApiError.notFound("{{P}} is not found");
                        }
                        return {{PV}};
                    }

                    private {{P}}DTO fetch{{P}}OrNull(Long id) {
                        if (id == null) {
                            return null;
                        }
                        try {
                            ApiResponseDTO response = {{PV}}Client.get{{P}}Detail(id);
                            if (response == null || response.getStatus() != ApiError.STATUS_SUCCESS
                                    || response.getData() == null) {
                                return null;
                            }
                            return objectMapper.convertValue(response.getData(), {{P}}DTO.class);
                        } catch (FeignException e) {
                            return null;
                        }
                    }

                """.replace("{{P}}", P).replace("{{PV}}", pv);
    }

    static String mapperMethods(Tbl t, boolean detail) {
        boolean nested = t.crossService && t.nestedInMain;
        String E = t.entity, v = t.var;
        StringBuilder b = new StringBuilder();
        b.append("    private void applyToEntity(").append(E).append("DTO dto, ").append(E).append(" ").append(v).append(") {\n");
        for (Col c : t.cols) {
            if (c == t.pk || c == t.statusCol || c.computed) continue;
            if (t.crossService && c == t.fkCol) continue;
            b.append("        if (dto.").append(c.getter()).append("() != null) ").append(v).append(".")
                    .append(c.setter()).append("(dto.").append(c.getter()).append("());\n");
        }
        b.append("    }\n\n");

        b.append("    private ").append(E).append("DTO toDto(").append(E).append(" ").append(v);
        if (nested) b.append(", ").append(t.parent.entity).append("DTO parent");
        b.append(") {\n");
        b.append("        ").append(E).append("DTO dto = new ").append(E).append("DTO();\n");
        for (Col c : t.cols) {
            if (nested && c == t.fkCol) continue;
            b.append("        dto.").append(c.setter()).append("(").append(v).append(".").append(c.getter()).append("());\n");
        }
        if (nested) b.append("        dto.set").append(t.parent.entity).append("(parent);\n");
        b.append("        return dto;\n    }\n");

        if (detail) {
            String P = t.parent.entity;
            b.append("\n    private ").append(E).append("DetailDTO toDetailDto(").append(E).append(" ").append(v)
                    .append(", ").append(P).append("DTO parent) {\n");
            b.append("        ").append(E).append("DetailDTO dto = new ").append(E).append("DetailDTO();\n");
            for (Col c : t.cols) {
                if (c == t.fkCol) continue;
                b.append("        dto.").append(c.setter()).append("(").append(v).append(".").append(c.getter()).append("());\n");
            }
            b.append("        dto.set").append(P).append("(parent);\n");
            b.append("        return dto;\n    }\n");
        }
        return b.toString();
    }

    // ----------------------------------------------------------- controller

    static String controller(Spec s, String pkg, Tbl t) {
        String E = t.entity, pkv = t.pk.javaName;
        boolean detail = t.crossService && t.hasDetailDto;
        int[] okCreate = s.code(t.sqlName + ".create.ok", new int[]{201, s.statusSuccess});
        int[] okUpdate = s.code(t.sqlName + ".update.ok", new int[]{200, s.statusSuccess});
        int[] okDetail = s.code(t.sqlName + ".detail.ok", new int[]{200, s.statusSuccess});
        int[] okDelete = s.code(t.sqlName + ".delete.ok", new int[]{200, s.statusSuccess});
        int[] okList = s.code(t.sqlName + ".list.ok", new int[]{200, s.statusSuccess});

        StringBuilder imp = new StringBuilder();
        imp.append("import ").append(pkg).append(".common.ApiError;\n");
        imp.append("import ").append(pkg).append(".dto.ApiResponseDTO;\n");
        imp.append("import ").append(pkg).append(".dto.").append(E).append("DTO;\n");
        for (Col c : t.filters) if (c.isEnum()) imp.append("import ").append(pkg).append(".entity.").append(c.enumClass).append(";\n");
        imp.append("import ").append(pkg).append(".service.").append(E).append("Service;\n");
        imp.append("import org.springframework.http.ResponseEntity;\n");
        imp.append("import org.springframework.web.bind.annotation.DeleteMapping;\n");
        imp.append("import org.springframework.web.bind.annotation.GetMapping;\n");
        imp.append("import org.springframework.web.bind.annotation.PathVariable;\n");
        imp.append("import org.springframework.web.bind.annotation.PostMapping;\n");
        imp.append("import org.springframework.web.bind.annotation.PutMapping;\n");
        imp.append("import org.springframework.web.bind.annotation.RequestBody;\n");
        imp.append("import org.springframework.web.bind.annotation.RequestMapping;\n");
        imp.append("import org.springframework.web.bind.annotation.RequestParam;\n");
        imp.append("import org.springframework.web.bind.annotation.RestController;\n");

        StringBuilder params = new StringBuilder();
        for (Col c : t.filters) {
            String type = c.isEnum() ? c.enumClass : c.javaType;
            params.append(",\n            @RequestParam(required = false) ").append(type).append(" ").append(c.javaName);
        }

        String svcVar = t.var + "Service";
        return """
                package {{PKG}}.controller;

                {{IMPORTS}}
                @RestController
                @RequestMapping("{{PATH}}")
                public class {{CTRL}} {

                    private final {{E}}Service {{SV}};

                    public {{CTRL}}({{E}}Service {{SV}}) {
                        this.{{SV}} = {{SV}};
                    }

                    @PostMapping
                    public ResponseEntity<ApiResponseDTO> create(@RequestBody {{E}}DTO dto) {
                        {{E}}DTO created = {{SV}}.create(dto);
                        return ResponseEntity.status({{CREATE_HTTP}})
                                .body(new ApiResponseDTO({{CREATE_ST}}, "{{E}} created successfully", created));
                    }

                    @PutMapping("/{{{PKV}}}")
                    public ResponseEntity<ApiResponseDTO> update(@PathVariable Long {{PKV}}, @RequestBody {{E}}DTO dto) {
                        {{E}}DTO updated = {{SV}}.update({{PKV}}, dto);
                        return ResponseEntity.status({{UPDATE_HTTP}})
                                .body(new ApiResponseDTO({{UPDATE_ST}}, "{{E}} updated successfully", updated));
                    }

                    @GetMapping("/{{{PKV}}}")
                    public ResponseEntity<ApiResponseDTO> getDetail(@PathVariable Long {{PKV}}) {
                        return ResponseEntity.status({{DETAIL_HTTP}})
                                .body(new ApiResponseDTO({{DETAIL_ST}}, "Successful", {{SV}}.getDetail({{PKV}})));
                    }

                    @DeleteMapping("/{{{PKV}}}")
                    public ResponseEntity<ApiResponseDTO> remove(@PathVariable Long {{PKV}}) {
                        {{SV}}.{{DELMETHOD}}({{PKV}});
                        return ResponseEntity.status({{DELETE_HTTP}})
                                .body(new ApiResponseDTO({{DELETE_ST}}, "{{DELMSG}}", null));
                    }

                    @GetMapping
                    public ResponseEntity<ApiResponseDTO> getAll(
                            @RequestParam(required = false, defaultValue = "0") Integer page,
                            @RequestParam(required = false, defaultValue = "10") Integer size{{PARAMS}}) {
                        return ResponseEntity.status({{LIST_HTTP}}).body(new ApiResponseDTO(
                                {{LIST_ST}}, "Successful", {{SV}}.getAll(page, size{{ARGS}})));
                    }
                }
                """
                .replace("{{PKG}}", pkg)
                .replace("{{IMPORTS}}", imp.toString())
                .replace("{{PATH}}", t.basePath)
                .replace("{{CTRL}}", t.controllerClass)
                .replace("{{E}}", E)
                .replace("{{SV}}", svcVar)
                .replace("{{PKV}}", pkv)
                .replace("{{DELMETHOD}}", deleteMethod(t))
                .replace("{{DELMSG}}", t.deleteStatus == null || t.deleteStatus.isBlank()
                        ? E + " deleted successfully"
                        : E + " set to " + t.deleteStatus + " successfully")
                .replace("{{CREATE_HTTP}}", String.valueOf(okCreate[0]))
                .replace("{{CREATE_ST}}", String.valueOf(okCreate[1]))
                .replace("{{UPDATE_HTTP}}", String.valueOf(okUpdate[0]))
                .replace("{{UPDATE_ST}}", String.valueOf(okUpdate[1]))
                .replace("{{DETAIL_HTTP}}", String.valueOf(okDetail[0]))
                .replace("{{DETAIL_ST}}", String.valueOf(okDetail[1]))
                .replace("{{DELETE_HTTP}}", String.valueOf(okDelete[0]))
                .replace("{{DELETE_ST}}", String.valueOf(okDelete[1]))
                .replace("{{LIST_HTTP}}", String.valueOf(okList[0]))
                .replace("{{LIST_ST}}", String.valueOf(okList[1]))
                .replace("{{PARAMS}}", params.toString())
                .replace("{{ARGS}}", filterParams(t));
    }

    static String globalExceptionHandler(Spec s, String pkg) {
        return """
                package {{PKG}}.config;

                import {{PKG}}.common.ApiError;
                import {{PKG}}.common.BusinessException;
                import {{PKG}}.dto.ApiResponseDTO;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;
                import org.springframework.http.converter.HttpMessageNotReadableException;
                import org.springframework.web.bind.MissingServletRequestParameterException;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestControllerAdvice;
                import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

                @RestControllerAdvice
                public class GlobalExceptionHandler {

                    @ExceptionHandler(BusinessException.class)
                    public ResponseEntity<ApiResponseDTO> handleBusiness(BusinessException ex) {
                        return ResponseEntity.status(ex.getHttpStatus())
                                .body(new ApiResponseDTO(ex.getApiStatus(), ex.getMessage(), null));
                    }

                    @ExceptionHandler(HttpMessageNotReadableException.class)
                    public ResponseEntity<ApiResponseDTO> handleNotReadable(HttpMessageNotReadableException ex) {
                        return ResponseEntity.status(ApiError.HTTP_VALIDATION)
                                .body(new ApiResponseDTO(ApiError.STATUS_VALIDATION,
                                        "Data validation failed: request body is invalid", null));
                    }

                    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
                    public ResponseEntity<ApiResponseDTO> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
                        return ResponseEntity.status(ApiError.HTTP_VALIDATION)
                                .body(new ApiResponseDTO(ApiError.STATUS_VALIDATION,
                                        "Data validation failed: invalid value for parameter '" + ex.getName() + "'", null));
                    }

                    @ExceptionHandler(MissingServletRequestParameterException.class)
                    public ResponseEntity<ApiResponseDTO> handleMissingParam(MissingServletRequestParameterException ex) {
                        return ResponseEntity.status(ApiError.HTTP_VALIDATION)
                                .body(new ApiResponseDTO(ApiError.STATUS_VALIDATION,
                                        "Data validation failed: missing parameter '" + ex.getParameterName() + "'", null));
                    }

                    @ExceptionHandler(Exception.class)
                    public ResponseEntity<ApiResponseDTO> handleGeneral(Exception ex) {
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(new ApiResponseDTO(ApiError.STATUS_SERVER_ERROR, "Internal server error", null));
                    }
                }
                """.replace("{{PKG}}", pkg);
    }

    static String openApiConfig(String pkg, String title) {
        return """
                package {{PKG}}.config;

                import io.swagger.v3.oas.models.OpenAPI;
                import io.swagger.v3.oas.models.info.Info;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class OpenApiConfig {

                    @Bean
                    public OpenAPI customOpenAPI() {
                        return new OpenAPI().info(new Info()
                                .title("{{TITLE}} API")
                                .version("1.0.0"));
                    }
                }
                """.replace("{{PKG}}", pkg).replace("{{TITLE}}", title);
    }

    // -------------------------------------------------------------- gateway

    static String gatewayPom(Spec s) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>fu.{{IDL}}</groupId>
                        <artifactId>{{IDL}}_parent_project</artifactId>
                        <version>1.0.0</version>
                        <relativePath>../../pom.xml</relativePath>
                    </parent>

                    <artifactId>{{GW}}</artifactId>
                    <packaging>jar</packaging>
                    <name>{{GW}}</name>

                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.cloud</groupId>
                            <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-security</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springdoc</groupId>
                            <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
                            <version>${springdoc.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-test</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.replace("{{IDL}}", s.studentIdLower).replace("{{GW}}", s.gatewayProject);
    }

    static String gatewayProps(Spec s) {
        StringBuilder routes = new StringBuilder();
        StringBuilder docs = new StringBuilder();
        int i = 0;
        for (Svc svc : s.services) {
            for (Tbl t : svc.tables) {
                String id = Names.lower(t.entity) + "-service";
                routes.append("spring.cloud.gateway.server.webflux.routes[").append(i).append("].id=").append(id).append("\n");
                routes.append("spring.cloud.gateway.server.webflux.routes[").append(i).append("].uri=http://localhost:")
                        .append(svc.port).append("\n");
                routes.append("spring.cloud.gateway.server.webflux.routes[").append(i).append("].predicates[0]=Path=")
                        .append(t.basePath).append(",").append(t.basePath).append("/**\n\n");
                i++;
            }
        }
        int j = 0;
        for (Svc svc : s.services) {
            docs.append("springdoc.swagger-ui.urls[").append(j).append("].name=").append(svc.project).append("\n");
            docs.append("springdoc.swagger-ui.urls[").append(j).append("].url=http://localhost:")
                    .append(svc.port).append("/v3/api-docs\n");
            j++;
        }
        return """
                spring.application.name={{GW}}
                server.port={{PORT}}

                {{ROUTES}}spring.cloud.gateway.server.webflux.globalcors.add-to-simple-url-handler-mapping=true
                spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowedOriginPatterns=*
                spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowedMethods=*
                spring.cloud.gateway.server.webflux.globalcors.cors-configurations.[/**].allowedHeaders=*

                springdoc.api-docs.path=/v3/api-docs
                springdoc.swagger-ui.path=/swagger-ui.html
                {{DOCS}}
                """
                .replace("{{GW}}", s.gatewayProject)
                .replace("{{PORT}}", String.valueOf(s.gatewayPort))
                .replace("{{ROUTES}}", routes.toString())
                .replace("{{DOCS}}", docs.toString());
    }

    static String gatewayApp(String pkg, Spec s) {
        return """
                package {{PKG}};

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class {{GW}}Application {

                    public static void main(String[] args) {
                        SpringApplication.run({{GW}}Application.class, args);
                    }
                }
                """.replace("{{PKG}}", pkg).replace("{{GW}}", s.gatewayProject);
    }

    static String gatewayConfig(String pkg) {
        return """
                package {{PKG}}.config;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
                import org.springframework.security.config.web.server.ServerHttpSecurity;
                import org.springframework.security.web.server.SecurityWebFilterChain;

                @Configuration
                @EnableWebFluxSecurity
                public class GatewayConfig {

                    @Bean
                    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
                        return http
                                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                                .build();
                    }
                }
                """.replace("{{PKG}}", pkg);
    }

    static String ideaCompiler() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project version="4">
                  <component name="CompilerConfiguration">
                    <annotationProcessing>
                      <profile default="true" name="Default" enabled="true" />
                    </annotationProcessing>
                    <bytecodeTargetLevel target="21" />
                  </component>
                </project>
                """;
    }

    static String ideaEncodings() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project version="4">
                  <component name="Encoding">
                    <file url="PROJECT" charset="UTF-8" />
                  </component>
                </project>
                """;
    }

    static String ideaMisc() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project version="4">
                  <component name="ExternalStorageConfigurationManager" enabled="true" />
                  <component name="MavenProjectsManager">
                    <option name="originalFiles">
                      <list>
                        <option value="$PROJECT_DIR$/pom.xml" />
                      </list>
                    </option>
                  </component>
                  <component name="ProjectRootManager" version="2" languageLevel="JDK_21" default="true" project-jdk-name="21" project-jdk-type="JavaSDK" />
                </project>
                """;
    }

    static String guide(Spec s) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(s.rootFolder).append(" - Huong dan\n\n");
        sb.append("Sinh tu Generator.java. Code khong co comment, moi giai thich nam o day.\n\n");
        sb.append("## Chay trong IntelliJ\n\n");
        sb.append("Mo thu muc goc `").append(s.rootFolder).append("` (khong mo tung service), Reload Maven.\n");
        sb.append("Bat Annotation Processing cho Lombok. Chay theo thu tu:\n\n");
        for (Svc svc : s.services) {
            sb.append(String.format("%d. `%sApplication` - port %d%n", s.services.indexOf(svc) + 1, svc.project, svc.port));
        }
        sb.append(String.format("%d. `%sApplication` - port %d (gateway, chay cuoi)%n%n",
                s.services.size() + 1, s.gatewayProject, s.gatewayPort));

        sb.append("## Bang ma loi (sua tai common/ApiError.java)\n\n");
        sb.append("| Loai | api status | HTTP |\n|---|---|---|\n");
        sb.append("| Thanh cong | ").append(s.statusSuccess).append(" | 200 / 201 |\n");
        sb.append("| Validation | ").append(s.gValidation[1]).append(" | ").append(s.gValidation[0]).append(" |\n");
        sb.append("| Trung du lieu | ").append(s.gDuplicated[1]).append(" | ").append(s.gDuplicated[0]).append(" |\n");
        sb.append("| Khong tim thay | ").append(s.gNotFound[1]).append(" | ").append(s.gNotFound[0]).append(" |\n");
        sb.append("| Trang thai sai | ").append(s.gNotAvailable[1]).append(" | ").append(s.gNotAvailable[0]).append(" |\n");
        sb.append("| Loi he thong | ").append(s.statusServerError).append(" | 500 |\n\n");

        sb.append("## API\n\n");
        for (Svc svc : s.services) {
            for (Tbl t : svc.tables) {
                sb.append("### ").append(t.entity).append(" - ").append(svc.project)
                        .append(" (port ").append(svc.port).append(")\n\n");
                sb.append("| Method | Path | Ghi chu |\n|---|---|---|\n");
                sb.append("| POST | `").append(t.basePath).append("` | tao moi");
                if (t.defaultStatus != null) sb.append(", status mac dinh `").append(t.defaultStatus).append("`");
                sb.append(" |\n");
                sb.append("| PUT | `").append(t.basePath).append("/{").append(t.pk.javaName).append("}` | partial update |\n");
                sb.append("| GET | `").append(t.basePath).append("/{").append(t.pk.javaName).append("}` | chi tiet");
                if (t.crossService && t.hasDetailDto) sb.append(" (tra ").append(t.entity).append("DetailDTO, long `")
                        .append(Names.camel(t.parent.entity)).append("`)");
                sb.append(" |\n");
                sb.append("| DELETE | `").append(t.basePath).append("/{").append(t.pk.javaName).append("}` | ");
                sb.append(t.deleteStatus == null || t.deleteStatus.isBlank() ? "xoa han"
                        : "set status = `" + t.deleteStatus + "`").append(" |\n");
                sb.append("| GET | `").append(t.basePath).append("` | phan trang");
                if (!t.filters.isEmpty()) {
                    sb.append(", loc: ").append(t.filters.stream().map(c -> "`" + c.javaName + "`"
                            + (t.partialFilters.contains(c.javaName) ? " (partial)" : ""))
                            .collect(Collectors.joining(", ")));
                }
                sb.append(" |\n\n");
                if (t.computedCol != null) {
                    sb.append("`").append(t.computedCol.javaName).append("` duoc tinh tu dong = `")
                            .append(t.parent.entity).append(".").append(t.priceCol.javaName).append("` x ")
                            .append(t.computeMode == 1 ? "so ngay giua 2 cot date" : "`" + (t.multiplierCol == null ? "?" : t.multiplierCol.javaName) + "`")
                            .append(". Client gui len se bi bo qua.\n\n");
                }
            }
        }

        sb.append("## Checklist truoc khi zip\n\n");
        sb.append("- [ ] Package deu la chu thuong `fu.").append(s.studentIdLower).append(".*`\n");
        sb.append("- [ ] Du 8 package: entity, repository, service, service.impl, controller, dto, config, common\n");
        sb.append("- [ ] packaging = jar o cac module con\n");
        sb.append("- [ ] application.properties khop bang cau hinh cua de\n");
        sb.append("- [ ] @Table / @Column khop file SQL\n");
        sb.append("- [ ] Swagger mo duoc o ca ").append(s.services.size() + 1).append(" project\n");
        sb.append("- [ ] Xoa thu muc target/ truoc khi zip\n");
        return sb.toString();
    }
}

// ======================================================================= out

class Out {
    static void banner() {
        System.out.println();
        System.out.println("===========================================================");
        System.out.println("  PE GENERATOR - sinh project Spring Boot Microservices");
        System.out.println("  Cach dung: java Generator.java [file.sql ...] [dap-an.txt]");
        System.out.println("===========================================================");
    }

    static void section(String s) {
        System.out.println();
        System.out.println("--- " + s + " " + "-".repeat(Math.max(0, 50 - s.length())));
    }

    static void info(String s) {
        System.out.println(s);
    }

    static void detail(String s) {
        System.out.println(s);
    }

    static void hint(String s) {
        System.out.println(s);
    }

    static void ok(String s) {
        System.out.println("[OK] " + s);
    }

    static void warn(String s) {
        System.out.println("[!]  " + s);
    }

    static void err(String s) {
        System.out.println("[X]  " + s);
    }

    static void file(String s) {
        System.out.println("   + " + s);
    }

    static void done(Spec s, Path root) {
        System.out.println();
        System.out.println("===========================================================");
        System.out.println("  XONG. Project: " + root.toAbsolutePath());
        System.out.println("  Dap an da luu vao dap-an.txt");
        System.out.println();
        System.out.println("  Buoc tiep theo:");
        System.out.println("    1. Mo thu muc '" + s.rootFolder + "' bang IntelliJ, Reload Maven");
        System.out.println("    2. Chay lenh: mvn -o clean package -DskipTests");
        System.out.println("    3. Chay lan luot cac service, gateway chay cuoi cung");
        System.out.println("    4. Doc file HUONG_DAN.md trong project");
        System.out.println();
        System.out.println("  Sinh lai nhanh:");
        System.out.println("    java Generator.java sql/*.txt dap-an.txt");
        System.out.println("===========================================================");
    }
}
