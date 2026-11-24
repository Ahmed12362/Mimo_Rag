"""Synthetic corpus content (original text, no real documents). Every entry is one rendered line, in logical order.

Each page: (page_id, direction, css_font_family, font_size_pt, lines). A line that is a list is a table row whose
cells are written in reading order (right-to-left for RTL pages); its ground truth is the cells joined by a space.
"""

AR_POLICY_1 = ("ar_policy_amiri", "rtl", "Amiri", 14, [
    "سياسة الإجازات السنوية",
    "يستحق الموظف إجازة سنوية مدفوعة الأجر بعد إتمام ستة أشهر",
    "من الخدمة المتصلة لدى الشركة.",
    "تبلغ مدة الإجازة السنوية واحدًا وعشرين يومًا في السنة،",
    "وتزداد إلى ثلاثين يومًا بعد عشر سنوات من الخدمة.",
    "يجب تقديم طلب الإجازة قبل أسبوعين على الأقل من تاريخ البدء،",
    "ويحق للمدير المباشر تأجيلها لظروف العمل مرة واحدة فقط.",
    "لا يجوز ترحيل أكثر من عشرة أيام إلى السنة التالية.",
    "تصرف قيمة الإجازات المستحقة غير المستخدمة عند انتهاء الخدمة.",
])

AR_POLICY_2 = ("ar_sick_naskh", "rtl", "Noto Naskh Arabic", 12, [
    "الإجازة المرضية",
    "يحصل الموظف على إجازة مرضية بأجر كامل لمدة 15 يومًا،",
    "ثم بثلاثة أرباع الأجر لمدة 30 يومًا تالية.",
    "يجب تقديم تقرير طبي معتمد خلال 48 ساعة من بداية الغياب.",
    "رقم الهاتف لقسم الموارد البشرية: 0225551234",
    "تاريخ السريان: 2026/01/01",
    "الحد الأقصى للإجازة المرضية ١٢٠ يومًا في السنة.",
    "تخصم أيام الغياب غير المبرر من الراتب الشهري.",
])

AR_DIACRITICS = ("ar_diacritics_amiri", "rtl", "Amiri", 16, [
    "الْعَمَلُ الْجَمَاعِيُّ أَسَاسُ النَّجَاحِ",
    "يَلْتَزِمُ الْمُوَظَّفُ بِالْحُضُورِ فِي الْمَوْعِدِ الْمُحَدَّدِ",
    "وَيُحَافِظُ عَلَى سِرِّيَّةِ الْمَعْلُومَاتِ وَالْوَثَائِقِ",
    "وَيَحْتَرِمُ زُمَلَاءَهُ وَعُمَلَاءَ الشَّرِكَةِ",
])

AR_SANS_SMALL = ("ar_hiring_sans10", "rtl", "Noto Sans Arabic", 10, [
    "إجراءات الاستقدام والتعيين",
    "تبدأ إجراءات التعيين بموافقة الإدارة المختصة على الوظيفة الشاغرة.",
    "تعلن الوظيفة داخليًا لمدة أسبوع قبل الإعلان الخارجي.",
    "تشكل لجنة المقابلات من ثلاثة أعضاء على الأقل.",
    "يخضع الموظف الجديد لفترة تجربة مدتها ثلاثة أشهر.",
    "يجوز تمديد فترة التجربة مرة واحدة بموافقة كتابية.",
    "تحفظ مستندات التعيين في ملف الموظف الإلكتروني.",
])

AR_TABLE = ("ar_table_naskh", "rtl", "Noto Naskh Arabic", 12, [
    "جدول أنواع الإجازات",
    ["البند", "المدة", "الملاحظات"],
    ["الإجازة السنوية", "21 يومًا", "تزداد بعد عشر سنوات"],
    ["الإجازة المرضية", "15 يومًا", "بأجر كامل"],
    ["إجازة الوضع", "90 يومًا", "مرتين خلال الخدمة"],
    ["إجازة الحج", "30 يومًا", "مرة واحدة"],
])

EN_POLICY_1 = ("en_leave_serif", "ltr", "Noto Serif", 12, [
    "Annual Leave Policy",
    "Employees are entitled to paid annual leave after six months",
    "of continuous service with the company.",
    "Annual leave is twenty-one days per year and increases to",
    "thirty days after ten years of service.",
    "Leave requests must be submitted at least two weeks in advance.",
    "No more than ten days may be carried over to the next year.",
])

EN_POLICY_2 = ("en_expenses_sans10", "ltr", "Noto Sans", 10, [
    "Expense Reimbursement (POL-2026-014)",
    "Claims must be filed within 30 days of the expense date.",
    "Receipts are required for any single item above EGP 250.",
    "Mileage is reimbursed at EGP 4.75 per kilometre.",
    "Questions: hr-support@example.com or extension 4410.",
    "Approved by the Finance Committee on 15 March 2026.",
])

MIXED_1 = ("mixed_ar_en_naskh", "rtl", "Noto Naskh Arabic", 12, [
    "سياسة العمل عن بعد",
    "يجب تسجيل الطلب في نظام HRIS قبل نهاية الشهر.",
    "يتم احتساب البدل وفق جدول Grade C بقيمة 1500 جنيه.",
    "للاستفسار راسل hr-support@example.com",
    "رقم السياسة: POL-2026-021 (الإصدار 3)",
    "يستخدم الموظف شبكة VPN المعتمدة فقط.",
])

MIXED_2 = ("mixed_lines_sans", "rtl", "Noto Sans Arabic", 12, [
    "ملخص السياسة",
    "Remote work is allowed up to two days per week.",
    "يسمح بالعمل عن بعد حتى يومين في الأسبوع.",
    "Managers approve requests in the HRIS portal.",
    "يوافق المدير على الطلبات من خلال بوابة HRIS.",
])

ARABIC_PAGES = [AR_POLICY_1, AR_POLICY_2, AR_DIACRITICS, AR_SANS_SMALL, AR_TABLE]
ENGLISH_PAGES = [EN_POLICY_1, EN_POLICY_2]
MIXED_PAGES = [MIXED_1, MIXED_2]
ALL_PAGES = ARABIC_PAGES + ENGLISH_PAGES + MIXED_PAGES


def ground_truth_lines(lines):
    return [" ".join(line) if isinstance(line, list) else line for line in lines]
