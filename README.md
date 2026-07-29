# PE Generator — sinh project Spring Boot Microservices từ file SQL

Tool 1 file, chạy thẳng bằng JDK 21, **không cần Maven, không cần thư viện ngoài, không cần mạng**.

---

# PHẦN 1 — QUY TRÌNH 90 PHÚT THI

Làm đúng thứ tự này. Đừng nhảy bước.

## Bước 0 (10 phút đầu, còn mạng) — tải dependency

Việc **duy nhất** cần mạng. Mở thư mục project mẫu bất kỳ đã sinh sẵn, hoặc:

```bash
cd <thư-mục-project-đã-sinh>
mvn dependency:go-offline
```

Không lỗi đỏ = an toàn ngắt mạng. **Chưa cần đọc kỹ đề lúc này.**

## Bước 1 — Chép file SQL của đề vào

```bash
cd PE_GENERATOR
# chép 2 file .txt/.sql đề cho vào thư mục sql/
```

## Bước 2 — Đọc đề, ghi ra giấy 8 thứ

Chỉ 8 thứ này, đừng đọc lan man:

1. Mã sinh viên + tên 3 project (mục *Project Name*)
2. Spring Boot / Spring Cloud version (mục *Framework*)
3. Tên database + port từng service (mục *Configuration for application.properties*)
4. Đường dẫn API mỗi entity (`/api/rooms`…)
5. **Bảng mã lỗi** (mục *Response Behavior*) — cột HTTP Code và Status
6. `DELETE` nghĩa là gì: xoá hẳn hay set status nào
7. Query param lọc của `GET` list — cái nào *partial match*
8. Rule nghiệp vụ đặc biệt (tính tiền, so sánh với bảng cha, giới hạn ngày)

## Bước 3 — Chạy tool

```bash
java Generator.java sql/*.txt
```

Trả lời theo giấy vừa ghi. Xong tool sinh project + lưu `dap-an.txt`.

## Bước 4 — Mở IntelliJ, build

Mở **thư mục gốc** vừa sinh (không mở từng service), Reload Maven, rồi:

```bash
mvn -o clean package -DskipTests
```

## Bước 5 — Chạy & test

Chạy lần lượt các service, **gateway chạy cuối cùng**. Test qua gateway.

## Bước 6 — Trước khi nộp

Xoá hết thư mục `target/`, đọc checklist trong `HUONG_DAN.md` mà tool sinh kèm.

---

# PHẦN 2 — CÁCH DÙNG CHI TIẾT

## Chạy tool

```bash
cd PE_GENERATOR
java Generator.java sql/*.txt
```

Không truyền tham số thì tool tự quét `sql/` rồi tới thư mục hiện tại, lấy mọi file `.sql`/`.txt` có chứa `CREATE TABLE`.

## Phím tắt khi trả lời

| Phím | Tác dụng |
|---|---|
| `Enter` | Nhận giá trị mặc định trong `[...]` |
| `!` | Nhận mặc định cho **toàn bộ** câu hỏi còn lại (kể cả câu đang gõ) |

### Chỉ 3 loại câu KHÔNG dùng `!` được — bắt buộc gõ tay

| Câu hỏi | Vì sao không có mặc định |
|---|---|
| Mã sinh viên | Duy nhất theo từng người, tool không đoán được |
| Port từng service (8081, 8082…) | Phải chắc mỗi service 1 port riêng |
| Port Gateway | Tương tự |

Gõ `!` ở 3 câu này **không bị bỏ qua** — tool cảnh báo và bắt gõ lại giá trị thật:

```text
Ma sinh vien (vd HE181534): !
[!]  Cau nay BAT BUOC nhap, khong co gia tri mac dinh de '!' nhan thay. Go gia tri that.
Ma sinh vien (vd HE181534):
```

### Mọi câu còn lại — kể cả version và DB — đều dùng `!` được

Spring Boot/Cloud/SpringDoc version, tên database, host, username, password, timezone, page size, tên entity, đường dẫn API, mã lỗi... tất cả đều có sẵn mặc định hợp lý (khớp với `~/.m2` đã tải sẵn và quy ước `sa/sa/localhost:1433` quen thuộc của môn này). Gõ `!` ở bất kỳ đâu trong nhóm này sẽ nhận mặc định cho **chính câu đó và mọi câu sau**.

> ⚠️ **Cân nhắc trước khi bấm `!`.** Một khi bấm, mọi câu sau — kể cả mã lỗi, đường dẫn API, giá trị status mặc định — đều bị nhận mặc định, dù mặc định đó có khớp đề hay không. Chỉ bấm khi đã chắc phần còn lại toàn dùng được giá trị gợi ý (xem lại giấy đã ghi ở Bước 2).

## Sinh lại sau khi sửa đáp án

```bash
java Generator.java sql/*.txt dap-an.txt
```

Mở `dap-an.txt`, sửa vài dòng, chạy lại — **không phải gõ lại câu nào**. Đây là cách nhanh nhất khi phát hiện đọc sót ý trong đề.

Có sẵn 2 file mẫu đối chiếu: `dap-an-mau-PE1.txt` (đề Hotel) và `dap-an-mau-LAB02.txt` (đề HR).

## Các mục hỏi, theo đúng thứ tự

| Mục | Hỏi gì | Chú ý |
|---|---|---|
| **0. Rà soát ràng buộc** | Báo constraint không đọc được + cho khai bù | Đọc kỹ bảng tổng kết cuối mục này |
| **1. Thông tin chung** | Mã SV, tên thư mục gốc, 3 version | Mã SV bắt buộc gõ, version dùng `!` được |
| **2. Database** | Tên DB, host, user, password | Có mặc định, nhớ đối chiếu tên DB với đề |
| **2b. Quy ước chung** | Timezone, page size mặc định/tối đa | Thường để mặc định |
| **3. Chia service** | Bảng nào thuộc project nào, port | Port từng service **bắt buộc gõ** |
| **4. Từng entity** | Path API, controller, status mặc định, DELETE, filter, range, format ngày, regex | Phần quan trọng nhất |
| **5. Liên kết Feign** | Khoá ngoại, chế độ DTO, rule cha–con, công thức tính tiền | Đọc kỹ mục 2.2 của đề |
| **6. Bảng mã lỗi** | HTTP + status cho từng dòng Response Behavior | Chép đúng bảng trong đề |

## Ba lỗi hay gặp nhất

**1. Bấm `!` rồi mới nhớ ra mã lỗi khác mặc định**
→ Không cần chạy lại từ đầu. Mở `dap-an.txt`, sửa dòng `*.validation.http=`, rồi replay.

**2. Giá trị status mặc định bị sai**
Tool lấy giá trị **đầu tiên** trong `CHECK IN (...)`. Đề Lab_02 có SQL ghi `LEFT, RETIRED, ACTIVE, INACTIVE` nên tool đề xuất `LEFT`, trong khi đề cần `ACTIVE`.
→ Luôn nhìn kỹ câu `Gia tri status mac dinh khi tao [...]`.

**3. Không thấy rule của đề trong code sinh ra**
Rule chỉ ghi trong file `.docx` (regex mã, giới hạn ngày…) thì SQL không có → tool không thể tự biết.
→ Khai ở **mục 0** bằng mini-DSL, xem [QUY_TAC_SQL.md](QUY_TAC_SQL.md).

## Đọc bảng tổng kết ở mục 0

Sau khi rà soát, tool in ra bảng này — **kiểm tra trước khi đi tiếp**:

```text
Ket qua sau ra soat:
   employees (8 cot)
      - status : NVARCHAR(10) NOT NULL ENUM[LEFT, RETIRED, ACTIVE, INACTIVE]
      - department_id : INT NOT NULL FK->departments
      * rule: end_date >= start_date
```

Thiếu `ENUM[...]`, thiếu `FK->`, hoặc thiếu dòng `* rule:` nào mà đề có yêu cầu → quay lại khai bổ sung ở mục 0, **đừng chạy tiếp**.

---

# PHẦN 3 — THAM CHIẾU

## Tool tự đọc được gì từ SQL

Không cần hỏi — parser tự suy ra:

- Tên bảng, tên cột, kiểu dữ liệu, độ dài `NVARCHAR(n)`, `DECIMAL(p,s)`
- `NOT NULL`, `IDENTITY`, `PRIMARY KEY`, `UNIQUE`
- Enum: cả `CHECK (col IN ('A','B'))` lẫn `CHECK (col='A' OR col='B')`
- `CHECK (col >= a AND col <= b)` và `CHECK (col BETWEEN a AND b)` → `requireInRange`
- `CHECK (LEN(col) <= n)` → `requireMaxLength`
- `CHECK (col > 0)` → `requirePositive`
- `CHECK (colA > colB)` trên 2 cột ngày → rule so sánh ngày
- `FOREIGN KEY ... REFERENCES` → **chiều gọi Feign** + kiểu `Long` cho cột FK
- `USE [db]` → tên database

Đọc được cả 2 kiểu viết: constraint **inline** trong `CREATE TABLE`, và constraint tách rời bằng `ALTER TABLE ADD CONSTRAINT` (kể cả khi thừa dấu cách).

### Constraint không đọc được thì sao?

Tool **báo ra màn hình**, không âm thầm bỏ qua:

```
[!]  Co 1 rang buoc trong SQL parser KHONG doc duoc:
   [departments] [effective_date] >= CAST(GETDATE() AS DATE)
```

Bạn khai bù ngay tại chỗ bằng mini-DSL (`code:regex=^[A-Za-z0-9]+$;capacity:range=1..10`), hoặc sửa file SQL.
Chi tiết đầy đủ: **[QUY_TAC_SQL.md](QUY_TAC_SQL.md)**.

Cách này cũng dùng để khai những rule **chỉ có trong đề .docx** mà SQL không diễn đạt được.

## Tool bắt buộc phải hỏi

Những thứ **không nằm trong SQL**, phải đọc từ đề:

- Mã sinh viên, tên project, tên package, port
- Đường dẫn API (`/api/rooms`)
- Bảng mã lỗi — hỏi từng dòng Response Behavior của từng endpoint
- `DELETE` nghĩa là gì: xoá hẳn hay set status (`MAINTENANCE` / `CANCELLED`)
- Giá trị status mặc định khi tạo
- Query param lọc cho GET list (thêm `*` để partial match, vd `guestName*`)
- Trạng thái bắt buộc của bảng cha khi tạo bản ghi con (vd room phải `AVAILABLE`)
- So sánh field con với field cha (vd `number_of_guests<=capacity`)
- Công thức cột tiền: `0` = client gửi, `1` = giá cha × số ngày giữa 2 cột date, `2` = giá cha × 1 cột số

## Tool sinh ra gì

```
<rootFolder>/
├── pom.xml                     parent, packaging=pom
├── HUONG_DAN.md                tự sinh theo đúng đề vừa nhập
├── .idea/                      bật sẵn annotation processing cho Lombok
├── services/<Project>/         mỗi service: 8 package đúng chuẩn đề
│   └── src/main/java/fu/<id>/<ten>/
│       ├── entity/ repository/ service/ service/impl/
│       └── controller/ dto/ config/ common/
└── infras/<Gateway>/           routes + CORS + Spring Security + Swagger
```

Mỗi service có đủ: 5 endpoint CRUD, `ApiResponseDTO`, `PageDTO`, `GlobalExceptionHandler`, `ApiError`, `ValidationUtils`, Swagger. Service có FK sang service khác được sinh thêm `service/client/<Parent>Client.java` (OpenFeign), DTO mirror, và `<Entity>DetailDTO` lồng object cha.

## Số entity

Linh hoạt. Mỗi bảng hỏi 1 lần "thuộc project nào" — đặt **cùng tên project** cho 2 bảng thì chúng nằm chung 1 service. Đề 3 bảng vẫn chạy được.

## Chế độ DTO khi có khoá ngoại

Đề khác nhau thể hiện quan hệ cha–con khác nhau, nên tool hỏi:

| Chế độ | Sinh ra | Đề dùng |
|---|---|---|
| `detail` | 2 DTO: `XDTO` có `parentId` phẳng + `XDetailDTO` lồng object (GET dùng bản lồng) | PE1 Hotel |
| `nested` | 1 DTO duy nhất, luôn lồng `ParentDTO` | Lab_02 HR |
| `flat` | 1 DTO, chỉ có `parentId` | khi đề không cần thông tin cha |

## Giới hạn — những thứ PHẢI sửa tay sau khi sinh

Tool **không im lặng bỏ qua**: các mục có 🔔 sẽ được cảnh báo ngay lúc chạy.

| Tình huống | Tool làm gì | Bạn cần làm |
|---|---|---|
| 🔔 Bảng có ≥2 khoá ngoại | chỉ sinh Feign/DTO lồng cho FK **đầu tiên** | thêm client thứ hai bằng tay |
| 🔔 Nhiều cột `UNIQUE` | chỉ check trùng cột đầu tiên | thêm `existsBy...` cho cột còn lại |
| 🔔 Bảng không khai PRIMARY KEY | tạm lấy cột đầu làm khoá chính | sửa lại nếu đoán sai |
| DTO lồng khi bảng cha **cùng** service | ép về `Long` phẳng | tự map thêm nếu đề cần |
| Tham số `sort=field,desc` | không sinh | thêm `Sort` vào `PageRequest` |
| Khoá chính tổ hợp | không sinh | dùng `@IdClass` / `@EmbeddedId` |
| Endpoint ngoài 5 CRUD | không sinh | viết thêm trong controller |
| List không phân trang (mảng thuần) | luôn trả `PageDTO` | sửa kiểu trả về |

## Đã kiểm chứng

**Bộ test tự động 16 kịch bản — 92/92 pass** (sinh code → build Maven → kiểm nội dung file sinh ra):

| Kịch bản | Kiểm điều gì |
|---|---|
| `pe1_hotel` | đề PE1 thật: mã 226/406, Feign, DetailDTO, tính tiền, rule ngày, partial filter |
| `lab02_hr` | đề Lab_02 thật: enum dạng OR, unique code, Feign, rule ngày |
| `dto_nested_mode` | 1 DTO lồng object, không sinh DetailDTO, lấy id từ object lồng |
| `single_table` | 1 bảng: không Feign, không check trùng, DELETE xoá hẳn |
| `weird_types` | BIGINT/BIT/FLOAT/NVARCHAR(MAX), cột tên `page` |
| `enum_or_datetime` | enum dạng OR, DATETIME có giờ, so sánh 2 cột **số** không bị nhầm thành rule ngày |
| `enum_invalid_ident` | giá trị enum có dấu cách/gạch → fallback String + `requireOneOf` |
| `three_services_chain` | 3 service, Feign 2 tầng, `BETWEEN`, 3 route gateway |
| `grouped_same_service` | gộp 2 bảng 1 service → không Feign gọi chính mình |
| `custom_error_codes` | đổi mã lỗi → `ApiError` cập nhật, vẫn gọi tập trung |
| `len_constraint` | `LEN(col) <= n` → `requireMaxLength` |
| `lab02_date_range` | rule ngày thật của Lab_02: `01/01/2000..+360` → `DateUtils.of` + `plusDaysFromNow` |
| `name_collision` | bảng `categories` + cột enum `category` → enum đổi tên, không ghi đè entity |
| `java_keyword` | cột `class`/`default`/`static` → field đổi tên, `@Column` giữ tên SQL |
| `no_pk_table` | bảng không khai PK → cảnh báo rõ, không NPE |
| `bit_float_required` | cột `BIT`/`FLOAT` NOT NULL → có `requireNotNull` |

**Chạy thật trên SQL Server:**

- Đề PE1 (rooms/reservations): **36/36 PASS** — đủ mã 1/2/3/4/5/0, Feign, Swagger 3 project, CORS
- Đề Lab_02 (departments/employees): **27/27 PASS** — mã 400/2, 400/3, object `department` lồng đúng ở cả POST và GET

## Ràng buộc kỹ thuật

- Chỉ chạy được với **JDK 21+** (single-file source launcher). JDK 21 chỉ nạp 1 file nên toàn bộ tool nằm trong `Generator.java`.
- Câu hỏi viết tiếng Việt **không dấu** để không vỡ font trên console Windows.
- Tool **ghi đè** thư mục đích nếu đã tồn tại (có hỏi xác nhận trước).
- Rule nghiệp vụ quá đặc thù (ngoài các mẫu ở trên) vẫn phải sửa tay trong `service/impl/*ServiceImpl.java` sau khi sinh.

## Chạy lại bộ test

```bash
cd PE_GENERATOR/test
python scenarios.py        # 16 kịch bản, phải 92/92 pass
```

Chạy sau mỗi lần bạn tự sửa `Generator.java`, để chắc không phá đề mẫu nào.
