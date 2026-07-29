# Quy tắc viết file SQL để tool đọc được 100%

Tool đọc file `.txt`/`.sql` của đề. Nếu đề viết theo cú pháp lạ, **tool sẽ báo ra màn hình** chứ không âm thầm bỏ qua:

```
[!]  Co 1 rang buoc trong SQL parser KHONG doc duoc:
   [departments] [effective_date] >= CAST(GETDATE() AS DATE)
```

Khi thấy dòng đó, bạn có **2 cách xử lý** — chọn cách nào cũng được.

---

## Cách 1 — Sửa file .txt theo cú pháp chuẩn

Nhanh nhất khi ràng buộc đó diễn đạt được bằng SQL.

### Bảng cú pháp tool đọc chắc chắn

| Ý nghĩa | Viết thế này |
|---|---|
| Khoá chính tự tăng | `[id] INT IDENTITY(1,1) NOT NULL` + `CONSTRAINT [PK_x] PRIMARY KEY CLUSTERED ([id] ASC)` |
| Bắt buộc nhập | `NOT NULL` |
| Độ dài tối đa | `NVARCHAR(50)` |
| Số thập phân | `DECIMAL(18,2)` |
| Duy nhất | `CONSTRAINT [UQ_x] UNIQUE NONCLUSTERED ([code] ASC)` |
| **Enum** | `CHECK ([status] IN ('ACTIVE','INACTIVE'))` |
| Enum (dạng OR — cũng đọc được) | `CHECK ([status]='ACTIVE' OR [status]='INACTIVE')` |
| Khoảng giá trị | `CHECK ([capacity] >= 1 AND [capacity] <= 10)` |
| Khoảng (BETWEEN — cũng đọc được) | `CHECK ([qty] BETWEEN 1 AND 99)` |
| Lớn hơn 0 | `CHECK ([price] > 0)` |
| Không âm | `CHECK ([amount] >= 0)` |
| Độ dài qua LEN | `CHECK (LEN([label]) <= 30)` |
| So sánh 2 cột | `CHECK ([check_out_date] > [check_in_date])` |
| **Khoá ngoại** | `FOREIGN KEY ([room_id]) REFERENCES [dbo].[rooms]([room_id])` |
| Tên database | `USE [MSS301_2026_PE]` |

Constraint viết **trong `CREATE TABLE`** hay tách riêng bằng **`ALTER TABLE ... ADD CONSTRAINT`** đều được. Thừa dấu cách, viết hoa/thường, có/không dấu `[]` đều không ảnh hưởng.

### Những gì tool KHÔNG đọc được (phải tự khai)

| Cú pháp | Vì sao |
|---|---|
| `CHECK ([date] >= CAST(GETDATE() AS DATE))` | phụ thuộc thời điểm chạy, không sinh code cố định được |
| `CHECK ([a] > [b])` với a, b **không phải cột ngày** | tool chỉ sinh rule so sánh cho cột ngày |
| `DEFAULT` | tool hỏi giá trị mặc định riêng, không lấy từ SQL |
| Trigger, computed column, function | ngoài phạm vi |

### Ví dụ sửa nhanh

Đề Lab_02 viết:
```sql
CONSTRAINT [CK_Departments_EffectiveDate]
    CHECK ([effective_date] >= CAST(GETDATE() AS DATE))
```
Ràng buộc này không sinh code được → **xoá đi** cho gọn, rồi tự khai bằng Cách 2 nếu đề yêu cầu validate.

---

## Cách 2 — Khai trực tiếp trong tool (không đụng file SQL)

Ngay đầu chương trình, mục **`0. RA SOAT RANG BUOC`**, tool hỏi từng bảng:

```
Rang buoc them cho bang 'departments' []:
```

Gõ theo cú pháp dưới đây, nhiều lệnh cách nhau bằng dấu `;`

| Muốn gì | Gõ |
|---|---|
| Danh sách giá trị cho phép | `status:enum=ACTIVE,INACTIVE,CLOSED` |
| Khoảng số | `capacity:range=1..10` |
| Chỉ có cận dưới | `floor:range=1..` |
| Độ dài tối đa | `name:maxlen=50` |
| Bắt buộc khớp định dạng | `code:regex=^[A-Za-z0-9]+$` |
| Bắt buộc nhập | `code:required` |
| Cho phép để trống | `location:optional` |
| Giá trị duy nhất | `code:unique` |
| Cột ngày này phải sau cột kia | `end_date>start_date` |
| **Giới hạn khoảng ngày** | `effective_date:dateRange=01/01/2000..+360` |

### Cú pháp mốc ngày trong `dateRange`

| Viết | Nghĩa |
|---|---|
| `01/01/2000` | ngày tuyệt đối (cũng nhận `2000-01-01`) |
| `+360` | hôm nay cộng 360 ngày |
| `-30` | hôm nay trừ 30 ngày |
| `now` hoặc `today` | đúng hôm nay |
| bỏ trống một vế | chỉ giới hạn một phía, vd `effective_date:dateRange=01/01/2000..` |

Ví dụ thực tế cho đề Lab_02 — cả hai rule dưới đây **chỉ có trong file .docx**, SQL không diễn đạt được:

- code *"only contains character (A-Z, a-z) and digits (0-9)"*
- effective_date *"must be after 2000/01/01 and before current date + 360"*

Gõ một lần cho bảng `departments`:

```text
code:regex=^[A-Za-z0-9]+$;effective_date:dateRange=01/01/2000..+360
```

Sinh ra đúng:

```java
ValidationUtils.requireMatches(dto.getCode(), "^[A-Za-z0-9]+$", "Code has invalid format");
requireEffectiveDateInRange(dto.getEffectiveDate());
// -> DateUtils.of(2000, 1, 1) va DateUtils.plusDaysFromNow(360)
```

> Đây là ưu điểm của Cách 2: khai được cả những rule **chỉ có trong đề .docx** mà SQL không diễn đạt được.

---

## Khuyến nghị dùng cách nào

| Tình huống | Nên dùng |
|---|---|
| SQL viết cú pháp lạ nhưng diễn đạt được | Cách 1 — sửa file, sạch và tái sử dụng được |
| Rule chỉ ghi trong đề .docx | Cách 2 — không thể sửa SQL cho việc này |
| Đang thi, cần nhanh | Cách 2 — không rời khỏi tool |
| Muốn lưu lại để lần sau chạy lại | Cả hai đều lưu vào `dap-an.txt`, replay được |

---

## Kiểm tra lại sau khi khai

Tool in ra bảng tổng kết ngay sau mục ra soát — **đọc kỹ dòng này trước khi đi tiếp**:

```
Ket qua sau ra soat:
   employees (8 cot)
      - status : NVARCHAR(10) NOT NULL ENUM[LEFT, RETIRED, ACTIVE, INACTIVE]
      - department_id : INT NOT NULL FK->departments
      * rule: end_date >= start_date
```

Nếu thiếu `ENUM[...]`, thiếu `FK->`, hoặc thiếu dòng `* rule:` nào mà đề có yêu cầu → quay lại khai bổ sung, đừng chạy tiếp.
