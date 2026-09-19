from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    Flowable,
    Image,
    KeepTogether,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)
from reportlab.lib.utils import ImageReader


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "output" / "pdf" / "mint-ai-recorder-user-manual.pdf"
ICON = ROOT / "docs" / "manual" / "assets" / "app-icon.png"
SCREEN_CURRENT = ROOT / "docs" / "manual" / "assets" / "meeting-detail-device.jpg"
SCREEN_OLD = ROOT / ".codex-remote-attachments" / "01a09a75-efe1-7121-94c9-f54f7fc078ba" / "f39d3030-c370-4e7c-b33c-11ddd8efae0e" / "1-Photo-1.jpg"
SCREEN_MEETING_LIST = ROOT / "docs" / "manual" / "assets" / "meeting-library-live.png"
SCREEN_RECORD = ROOT / "docs" / "manual" / "assets" / "record-screen-live.png"
SCREEN_SETTINGS = ROOT / "docs" / "manual" / "assets" / "settings-screen-live.png"
SCREEN_DETAIL = ROOT / "docs" / "manual" / "assets" / "meeting-detail-live.png"
SCREEN_DETAIL_TIMESTAMPS = ROOT / "docs" / "manual" / "assets" / "meeting-detail-timestamps-live.png"

GREEN = colors.HexColor("#0C7A58")
GREEN_DARK = colors.HexColor("#07523D")
GREEN_PALE = colors.HexColor("#E7F4EF")
INK = colors.HexColor("#17211D")
MUTED = colors.HexColor("#5A6862")
LINE = colors.HexColor("#C7D8D1")
SURFACE = colors.HexColor("#F4F8F6")
RED = colors.HexColor("#A63D40")
WHITE = colors.white


def register_fonts():
    pdfmetrics.registerFont(TTFont("JP", r"C:\Windows\Fonts\meiryo.ttc", subfontIndex=0))
    pdfmetrics.registerFont(TTFont("JP-Bold", r"C:\Windows\Fonts\meiryob.ttc", subfontIndex=0))


class PhoneMockup(Flowable):
    def __init__(self, title, rows, width=76 * mm, height=126 * mm):
        super().__init__()
        self.title = title
        self.rows = rows
        self.width = width
        self.height = height

    def draw(self):
        c = self.canv
        w, h = self.width, self.height
        c.saveState()
        c.setFillColor(colors.HexColor("#0C1511"))
        c.roundRect(0, 0, w, h, 8 * mm, fill=1, stroke=0)
        c.setFillColor(colors.HexColor("#1B2A23"))
        c.roundRect(3 * mm, 3 * mm, w - 6 * mm, h - 6 * mm, 5 * mm, fill=1, stroke=0)
        c.setFillColor(WHITE)
        c.setFont("JP-Bold", 11)
        c.drawString(8 * mm, h - 14 * mm, self.title)
        y = h - 24 * mm
        for label, kind in self.rows:
            if kind == "primary":
                fill, text_color = GREEN, WHITE
            elif kind == "danger":
                fill, text_color = colors.HexColor("#5B2528"), colors.HexColor("#FFDCDD")
            elif kind == "heading":
                c.setFillColor(colors.HexColor("#DDE8E2"))
                c.setFont("JP-Bold", 8.5)
                c.drawString(8 * mm, y, label)
                y -= 8 * mm
                continue
            else:
                fill, text_color = colors.HexColor("#26382F"), colors.HexColor("#ECF3EF")
            box_h = 12 * mm if kind != "large" else 28 * mm
            c.setFillColor(fill)
            c.roundRect(7 * mm, y - box_h + 3 * mm, w - 14 * mm, box_h, 3 * mm, fill=1, stroke=0)
            c.setFillColor(text_color)
            c.setFont("JP-Bold" if kind == "primary" else "JP", 7.4)
            c.drawCentredString(w / 2, y - box_h / 2 + 1.5 * mm, label)
            y -= box_h + 4 * mm
            if y < 10 * mm:
                break
        c.restoreState()


class ScreenshotCrop(Flowable):
    """Displays a crop without modifying the source screenshot."""

    def __init__(self, path, crop_top, crop_bottom, width=80 * mm):
        super().__init__()
        self.path = str(path)
        self.reader = ImageReader(self.path)
        self.iw, self.ih = self.reader.getSize()
        self.x0 = 0
        self.x1 = self.iw
        self.top = crop_top
        self.bottom = crop_bottom
        crop_h = max(1, crop_bottom - crop_top)
        self.width = width
        self.height = width * crop_h / self.iw

    def draw(self):
        c = self.canv
        scale = self.width / (self.x1 - self.x0)
        p = c.beginPath()
        p.roundRect(0, 0, self.width, self.height, 4 * mm)
        c.saveState()
        c.clipPath(p, stroke=0, fill=0)
        draw_y = -(self.ih - self.bottom) * scale
        c.drawImage(
            self.reader,
            -self.x0 * scale,
            draw_y,
            width=self.iw * scale,
            height=self.ih * scale,
            preserveAspectRatio=False,
            mask="auto",
        )
        c.restoreState()
        c.setStrokeColor(LINE)
        c.setLineWidth(0.8)
        c.roundRect(0, 0, self.width, self.height, 4 * mm, fill=0, stroke=1)


def build_styles():
    base = getSampleStyleSheet()
    return {
        "title": ParagraphStyle(
            "title", parent=base["Title"], fontName="JP-Bold", fontSize=25,
            leading=33, textColor=GREEN_DARK, alignment=TA_LEFT, wordWrap="CJK"
        ),
        "subtitle": ParagraphStyle(
            "subtitle", parent=base["Normal"], fontName="JP", fontSize=11,
            leading=18, textColor=MUTED, wordWrap="CJK"
        ),
        "h1": ParagraphStyle(
            "h1", parent=base["Heading1"], fontName="JP-Bold", fontSize=18,
            leading=24, textColor=GREEN_DARK, spaceAfter=8, wordWrap="CJK"
        ),
        "h2": ParagraphStyle(
            "h2", parent=base["Heading2"], fontName="JP-Bold", fontSize=12.5,
            leading=18, textColor=INK, spaceBefore=7, spaceAfter=4, wordWrap="CJK"
        ),
        "body": ParagraphStyle(
            "body", parent=base["BodyText"], fontName="JP", fontSize=9.4,
            leading=15.2, textColor=INK, spaceAfter=4, wordWrap="CJK"
        ),
        "small": ParagraphStyle(
            "small", parent=base["BodyText"], fontName="JP", fontSize=7.7,
            leading=12, textColor=MUTED, wordWrap="CJK"
        ),
        "bullet": ParagraphStyle(
            "bullet", parent=base["BodyText"], fontName="JP", fontSize=9.1,
            leading=14.5, textColor=INK, leftIndent=14, firstLineIndent=-8,
            bulletIndent=2, spaceAfter=2.5, wordWrap="CJK"
        ),
        "step": ParagraphStyle(
            "step", parent=base["BodyText"], fontName="JP", fontSize=9.3,
            leading=15, textColor=INK, leftIndent=20, firstLineIndent=-15,
            spaceAfter=5, wordWrap="CJK"
        ),
        "callout": ParagraphStyle(
            "callout", parent=base["BodyText"], fontName="JP", fontSize=9,
            leading=14.5, textColor=GREEN_DARK, leftIndent=10, rightIndent=10,
            borderColor=LINE, borderWidth=0.8, borderPadding=8,
            backColor=GREEN_PALE, spaceBefore=5, spaceAfter=8, wordWrap="CJK"
        ),
        "caption": ParagraphStyle(
            "caption", parent=base["BodyText"], fontName="JP", fontSize=7.6,
            leading=11, textColor=MUTED, alignment=TA_CENTER, spaceBefore=3, wordWrap="CJK"
        ),
    }


def p(text, styles):
    return Paragraph(text, styles["body"])


def h1(text, styles):
    return Paragraph(text, styles["h1"])


def h2(text, styles):
    return Paragraph(text, styles["h2"])


def bullet(text, styles):
    return Paragraph(f"• {text}", styles["bullet"])


def step(number, text, styles):
    return Paragraph(f"<b>{number}</b>　{text}", styles["step"])


def callout(text, styles):
    return Paragraph(text, styles["callout"])


def on_page(canvas, doc):
    canvas.saveState()
    page = canvas.getPageNumber()
    if page > 1:
        canvas.setStrokeColor(LINE)
        canvas.line(18 * mm, A4[1] - 14 * mm, A4[0] - 18 * mm, A4[1] - 14 * mm)
        canvas.setFont("JP-Bold", 8)
        canvas.setFillColor(GREEN_DARK)
        canvas.drawString(18 * mm, A4[1] - 10.5 * mm, "mint AI レコーダー 操作説明書")
    canvas.setFont("JP", 7.5)
    canvas.setFillColor(MUTED)
    canvas.drawRightString(A4[0] - 18 * mm, 10 * mm, f"{page}")
    canvas.restoreState()


def make_pdf():
    register_fonts()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    styles = build_styles()
    doc = SimpleDocTemplate(
        str(OUT), pagesize=A4,
        leftMargin=18 * mm, rightMargin=18 * mm,
        topMargin=20 * mm, bottomMargin=17 * mm,
        title="mint AI レコーダー 操作説明書",
        author="mint AI レコーダー",
        subject="録音・文字起こし・議事録作成アプリの操作説明書",
    )
    story = []

    # Cover
    story += [Spacer(1, 18 * mm)]
    icon = Image(str(ICON), width=32 * mm, height=32 * mm)
    story += [icon, Spacer(1, 10 * mm)]
    story += [Paragraph("mint AI レコーダー", styles["title"])]
    story += [Paragraph("操作説明書", ParagraphStyle(
        "cover-sub", parent=styles["title"], fontSize=19, leading=25, textColor=INK
    ))]
    story += [Spacer(1, 8 * mm)]
    story += [Paragraph(
        "録音、文字起こし、話者分離、議事録作成、再生、編集、書き出し、バックアップまでを、画面の流れに沿って説明します。",
        styles["subtitle"]
    )]
    story += [Spacer(1, 28 * mm)]
    cover_table = Table([
        ["対象", "Android 8.0以上 / 64-bit ARM"],
        ["説明書版", "2026-09-19"],
        ["基本方針", "録音は暗号化保存。AI処理は必要な場合だけ実行"],
    ], colWidths=[30 * mm, 110 * mm])
    cover_table.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, -1), "JP"),
        ("FONTSIZE", (0, 0), (-1, -1), 9),
        ("TEXTCOLOR", (0, 0), (0, -1), GREEN_DARK),
        ("FONTNAME", (0, 0), (0, -1), "JP-Bold"),
        ("LINEBELOW", (0, 0), (-1, -1), 0.5, LINE),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]))
    story += [cover_table, Spacer(1, 14 * mm)]
    story += [callout("外部に送信したくない情報がある場合は、アプリロック、スクリーンショット保護、音声外部送信保護を有効にし、必要に応じてローカルAIを選択してください。", styles)]
    story += [PageBreak()]

    # Quick start
    story += [h1("1　最短で使う", styles)]
    story += [p("録音だけならAIモデル、APIキー、ネットワーク接続は不要です。議事録が必要なときだけAI処理を追加します。", styles)]
    quick_rows = [
        ["1", "新しい録音", "会議一覧から録音画面を開く"],
        ["2", "録音開始", "マイク権限を許可し、録音する"],
        ["3", "停止して保存", "暗号化録音を確定する"],
        ["4", "AI処理（任意）", "文字起こしと議事録を作成する"],
        ["5", "再生・詳細", "時刻再生、編集、書き出しを行う"],
    ]
    qt = Table(quick_rows, colWidths=[12 * mm, 42 * mm, 105 * mm], repeatRows=0)
    qt.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, -1), "JP"),
        ("FONTSIZE", (0, 0), (-1, -1), 9),
        ("FONTNAME", (0, 0), (1, -1), "JP-Bold"),
        ("TEXTCOLOR", (0, 0), (0, -1), WHITE),
        ("BACKGROUND", (0, 0), (0, -1), GREEN),
        ("BACKGROUND", (1, 0), (-1, -1), SURFACE),
        ("GRID", (0, 0), (-1, -1), 0.5, LINE),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]))
    story += [qt, Spacer(1, 8 * mm)]
    story += [h2("初回だけ必要な操作", styles)]
    story += [bullet("録音開始時にマイク権限を許可します。", styles)]
    story += [bullet("通知権限は、録音中の状態表示と停止操作に使います。", styles)]
    story += [bullet("アプリロックが有効な場合は、端末の認証で解除します。", styles)]
    story += [callout("「録音だけ保存して終了」を選べば、AIへ何も送信せず録音だけを残せます。", styles)]
    story += [PageBreak()]

    # Meeting list
    story += [h1("2　会議一覧", styles)]
    meeting_list_screen = ScreenshotCrop(
        SCREEN_MEETING_LIST, crop_top=120, crop_bottom=1880, width=76 * mm
    )
    left = [h2("画面でできること", styles),
            bullet("新しい録音を開始する", styles),
            bullet("保存済み会議を開く", styles),
            bullet("複数の会議を選んで削除する", styles),
            bullet("設定画面を開く", styles),
            Spacer(1, 4 * mm),
            callout("会議名は議事録タイトルがあればそのタイトルを使い、未解析の場合は日時を表示します。", styles)]
    two = Table([[left, meeting_list_screen]], colWidths=[92 * mm, 76 * mm], hAlign="LEFT")
    two.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0,0),(-1,-1),0), ("RIGHTPADDING", (0,0),(-1,-1),4)]))
    story += [two, Spacer(1, 4 * mm)]
    story += [h2("会議を削除する", styles)]
    story += [step("1", "「会議を選択」を押します。", styles)]
    story += [step("2", "削除する会議へチェックを付けます。", styles)]
    story += [step("3", "削除を押し、対象と件数を確認します。", styles)]
    story += [p("削除は暗号鍵の破棄を伴い、取り消せません。書き出したPDF、Markdown、WAVは別の保存先に残ります。", styles)]
    story += [PageBreak()]

    # Recording
    story += [h1("3　録音する", styles)]
    record_screen = ScreenshotCrop(
        SCREEN_RECORD, crop_top=120, crop_bottom=1660, width=80 * mm
    )
    instructions = [
        h2("録音の手順", styles),
        step("1", "「録音開始」を押します。", styles),
        step("2", "経過時間と入力音量を確認します。", styles),
        step("3", "必要に応じて一時停止と録音再開を使います。", styles),
        step("4", "「停止して保存」を押します。", styles),
        step("5", "録音だけ残すか、AI処理を実行するか選びます。", styles),
        h2("話者数", styles),
        p("空欄なら自動判定です。人数が分かっている場合は数値を入力すると話者分離が安定しやすくなります。", styles),
    ]
    two = Table([[record_screen, instructions]], colWidths=[80 * mm, 88 * mm], hAlign="LEFT")
    two.setStyle(TableStyle([("VALIGN", (0,0),(-1,-1),"TOP"), ("LEFTPADDING",(0,0),(-1,-1),0), ("RIGHTPADDING",(0,0),(-1,-1),4)]))
    story += [two]
    story += [callout("録音中はAndroidの通知から一時停止、再開、停止ができます。本文や会議名は通知へ表示しません。", styles)]
    story += [h2("処理結果の折りたたみ", styles)]
    story += [p("AI処理後の結果は最初の8行だけ表示します。「全体を見る」で全文を開き、「折りたたむ」で短い表示へ戻します。", styles)]
    story += [PageBreak()]

    # AI choices
    story += [h1("4　AI処理を選ぶ", styles)]
    story += [p("音声認識、テキスト整形、議事録作成の工程ごとにProviderとモデルを選びます。", styles)]
    ai_table = Table([
        ["比較", "ローカルAI", "クラウドAI"],
        ["送信", "録音・本文を端末外へ送信しない", "必要な音声または本文をProviderへ送信"],
        ["準備", "モデル導入と空き容量が必要", "APIキー、モデル名、ネット接続が必要"],
        ["特徴", "プライバシーを優先", "端末負荷を抑えやすい"],
        ["注意", "長い録音では処理時間と電池消費が増える", "Providerの保存・学習・削除条件も確認する"],
    ], colWidths=[25 * mm, 68 * mm, 68 * mm], repeatRows=1)
    ai_table.setStyle(TableStyle([
        ("FONTNAME", (0,0),(-1,-1),"JP"), ("FONTSIZE",(0,0),(-1,-1),8.2),
        ("FONTNAME", (0,0),(-1,0),"JP-Bold"), ("FONTNAME",(0,1),(0,-1),"JP-Bold"),
        ("BACKGROUND",(0,0),(-1,0),GREEN_DARK), ("TEXTCOLOR",(0,0),(-1,0),WHITE),
        ("BACKGROUND",(0,1),(-1,-1),SURFACE), ("GRID",(0,0),(-1,-1),0.5,LINE),
        ("VALIGN",(0,0),(-1,-1),"TOP"), ("TOPPADDING",(0,0),(-1,-1),6), ("BOTTOMPADDING",(0,0),(-1,-1),6),
    ]))
    story += [ai_table, Spacer(1, 6 * mm)]
    story += [h2("音声外部送信保護", styles)]
    story += [p("初期状態はONです。クラウド音声認識を使う場合だけ、送信先、モデル、送信内容を確認して許可します。アプリ再起動後は再びONへ戻ります。", styles)]
    story += [h2("処理順", styles)]
    story += [bullet("1. 音声認識", styles), bullet("2. 端末内の話者分離", styles), bullet("3. テキスト整形", styles), bullet("4. 議事録作成", styles)]
    story += [callout("アプリはProviderを自動で切り替えません。処理できない場合は設定を確認してから再実行します。", styles)]
    story += [PageBreak()]

    # Detail + screenshot
    story += [h1("5　議事録と文字起こしを見る", styles)]
    top_crop = ScreenshotCrop(SCREEN_DETAIL_TIMESTAMPS, crop_top=0, crop_bottom=1500, width=77 * mm)
    detail_text = [
        h2("折りたたみ表示", styles),
        bullet("議事録の要約：最初の6行", styles),
        bullet("文字起こし：最初の8行", styles),
        bullet("「全体を見る／折りたたむ」で切り替え", styles),
        h2("タイムスタンプ再生", styles),
        p("下線付きの開始時刻をタップすると、その発話位置から録音を再生します。対象が折りたたみ部分にある場合は自動で展開します。", styles),
        h2("通常再生", styles),
        p("再生、一時停止、10秒戻し、10秒送り、位置スライダーを利用できます。", styles),
    ]
    two = Table([[top_crop, detail_text]], colWidths=[82 * mm, 86 * mm], hAlign="LEFT")
    two.setStyle(TableStyle([("VALIGN",(0,0),(-1,-1),"TOP"), ("LEFTPADDING",(0,0),(-1,-1),0), ("RIGHTPADDING",(0,0),(-1,-1),4)]))
    story += [two]
    story += [Paragraph("実機画面：下線付き時刻から発話位置を再生できます。", styles["caption"])]
    story += [Spacer(1, 5 * mm)]
    story += [h2("要約の根拠", styles)]
    story += [p("「要約の根拠時刻」から、目的、議題、決定事項、対応項目などの根拠発話へ移動できます。音声が残っていれば、その位置から再生できます。", styles)]
    story += [PageBreak()]

    # Edit/export
    story += [h1("6　編集・版管理・書き出し", styles)]
    story += [h2("文字起こしを編集する", styles)]
    story += [step("1", "「編集する」を押します。", styles)]
    story += [step("2", "本文を修正します。", styles)]
    story += [step("3", "「編集を保存」を押します。", styles)]
    story += [p("編集内容は新しい版として保存されます。「版を比較」から以前の版との差分確認と復元ができます。", styles)]
    story += [h2("議事録を書き出す", styles)]
    export_table = Table([
        ["形式", "用途", "注意"],
        ["Markdown", "編集しやすいテキスト", "保存先アプリの管理対象"],
        ["PDF", "共有・印刷しやすい文書", "暗号化バックアップではない"],
        ["WAV", "録音音声の再生・編集", "平文音声として保存される"],
    ], colWidths=[28 * mm, 65 * mm, 68 * mm], repeatRows=1)
    export_table.setStyle(TableStyle([
        ("FONTNAME",(0,0),(-1,-1),"JP"), ("FONTNAME",(0,0),(-1,0),"JP-Bold"),
        ("FONTSIZE",(0,0),(-1,-1),8.5), ("BACKGROUND",(0,0),(-1,0),GREEN_DARK),
        ("TEXTCOLOR",(0,0),(-1,0),WHITE), ("BACKGROUND",(0,1),(-1,-1),SURFACE),
        ("GRID",(0,0),(-1,-1),0.5,LINE), ("VALIGN",(0,0),(-1,-1),"TOP"),
        ("TOPPADDING",(0,0),(-1,-1),6), ("BOTTOMPADDING",(0,0),(-1,-1),6),
    ]))
    story += [export_table, Spacer(1, 6 * mm)]
    story += [callout("書き出したファイルはアプリの暗号化と削除の対象外です。保存先、共有先、写真・ファイル同期の設定を確認してください。", styles)]
    story += [h2("音声だけ削除する", styles)]
    story += [p("文字起こし、議事録、編集履歴を残したまま録音音声だけ削除できます。削除後は再生、再文字起こし、再話者分離ができません。", styles)]
    story += [PageBreak()]

    # Settings
    story += [h1("7　設定", styles)]
    settings_screen = ScreenshotCrop(
        SCREEN_SETTINGS, crop_top=120, crop_bottom=1710, width=78 * mm
    )
    settings_text = [
        h2("言語", styles), p("日本語と英語を切り替えます。英語表示ではAIへの指示も英語になります。", styles),
        h2("AIの処理順", styles), p("工程ごとにProviderとモデルを選び、「AI設定を保存」を押します。", styles),
        h2("ローカルモデル", styles), p("推奨モデルの導入、ファイル/HTTPS URLからの追加、選択、再検証、削除を行います。", styles),
        h2("クラウド接続", styles), p("OpenAI/GeminiのAPIキーと工程別モデル名を管理します。APIキーは端末内で暗号化します。", styles),
        h2("外観", styles), p("ライト、ダーク、端末設定に合わせる、から選べます。", styles),
    ]
    two = Table([[settings_text, settings_screen]], colWidths=[90 * mm, 78 * mm], hAlign="LEFT")
    two.setStyle(TableStyle([("VALIGN",(0,0),(-1,-1),"TOP"), ("LEFTPADDING",(0,0),(-1,-1),0), ("RIGHTPADDING",(0,0),(-1,-1),4)]))
    story += [two]
    story += [PageBreak()]

    # Widget/security
    story += [h1("8　ウィジェットとセキュリティ", styles)]
    story += [h2("ホーム画面ウィジェット", styles)]
    widget_table = Table([
        ["状態", "操作"],
        ["待機中", "録音開始 / AI処理"],
        ["録音中", "一時停止 / 停止して保存"],
        ["一時停止中", "録音再開 / 停止して保存"],
        ["AI処理中", "状態変更ボタンを無効化"],
    ], colWidths=[48 * mm, 112 * mm], repeatRows=1)
    widget_table.setStyle(TableStyle([
        ("FONTNAME",(0,0),(-1,-1),"JP"), ("FONTNAME",(0,0),(-1,0),"JP-Bold"),
        ("FONTSIZE",(0,0),(-1,-1),8.8), ("BACKGROUND",(0,0),(-1,0),GREEN_DARK),
        ("TEXTCOLOR",(0,0),(-1,0),WHITE), ("GRID",(0,0),(-1,-1),0.5,LINE),
        ("BACKGROUND",(0,1),(-1,-1),SURFACE), ("TOPPADDING",(0,0),(-1,-1),6), ("BOTTOMPADDING",(0,0),(-1,-1),6),
    ]))
    story += [widget_table, Spacer(1, 7 * mm)]
    story += [h2("保護機能", styles)]
    story += [bullet("アプリロック：5分以上バックグラウンドになった後に再認証", styles)]
    story += [bullet("スクリーンショット保護：初期状態では撮影を禁止", styles)]
    story += [bullet("音声外部送信保護：初期状態ではクラウドへの音声送信を禁止", styles)]
    story += [bullet("会議ごとのAES-256-GCM暗号化とAndroid Keystoreによる鍵保護", styles)]
    story += [bullet("音声鍵と本文鍵を分離し、音声だけ削除可能", styles)]
    story += [callout("通常のPDF、Markdown、WAVは暗号化バックアップではありません。機密データは保存先でも適切に保護してください。", styles)]
    story += [PageBreak()]

    # Backup
    story += [h1("9　暗号化バックアップ", styles)]
    story += [step("1", "設定の「データ管理」を開きます。", styles)]
    story += [step("2", "「会議を選んで暗号化バックアップ」を押します。", styles)]
    story += [step("3", "対象の会議と保存先を選びます。", styles)]
    story += [step("4", "強いパスフレーズを設定します。", styles)]
    story += [step("5", "回復キーを使う場合は、バックアップとは別の安全な場所へ保存します。", styles)]
    story += [h2("バックアップに含まれないもの", styles)]
    story += [bullet("APIキー、OAuthトークン", styles)]
    story += [bullet("Android Keystoreの鍵", styles)]
    story += [bullet("話者埋め込み（声紋特徴）", styles)]
    story += [bullet("音声外部送信保護のOFF状態", styles)]
    story += [callout("パスフレーズと回復キーの両方を失うと、開発者を含め誰も復元できません。", styles)]
    story += [h2("復元", styles)]
    story += [p("「暗号化バックアップから復元」を選び、ファイルと認証情報を指定します。アプリは改ざんと必要容量を確認してから復元し、失敗時は既存データを変更しません。", styles)]
    story += [PageBreak()]

    # Troubleshooting with legacy screenshot
    story += [h1("10　困ったとき", styles)]
    lower_crop = ScreenshotCrop(SCREEN_CURRENT, crop_top=360, crop_bottom=1040, width=67 * mm)
    trouble = [
        h2("タイムスタンプから再生できない", styles),
        bullet("会議詳細を閉じて開き直す", styles),
        bullet("音声データが残っているか確認する", styles),
        bullet("古い解析結果は「録音を再解析」する", styles),
        h2("SPEAKER_UNKNOWNになる", styles),
        bullet("CAM++話者分離モデルを確認する", styles),
        bullet("分かる場合は話者数を指定する", styles),
        bullet("更新版で録音を再解析する", styles),
        h2("モデルが一覧に出ない", styles),
        bullet("導入済みモデルを再検証する", styles),
        bullet("工程別のモデル選択画面を開き直す", styles),
    ]
    two = Table([[lower_crop, trouble]], colWidths=[72 * mm, 96 * mm], hAlign="LEFT")
    two.setStyle(TableStyle([("VALIGN",(0,0),(-1,-1),"TOP"), ("LEFTPADDING",(0,0),(-1,-1),0), ("RIGHTPADDING",(0,0),(-1,-1),4)]))
    story += [two]
    story += [Paragraph("旧解析結果で開始・終了が同じ時刻になる例。更新版では1秒区間へ補正し、再生できます。", styles["caption"])]
    story += [Spacer(1, 4 * mm)]
    story += [h2("AI処理を開始できない", styles)]
    story += [p("録音の保存完了、選択モデル、APIキー、モデル名、ネットワーク、音声外部送信保護を順に確認します。", styles)]
    story += [PageBreak()]

    # Final reference
    story += [h1("11　重要事項と用語", styles)]
    story += [h2("削除の違い", styles)]
    delete_table = Table([
        ["操作", "残るもの", "できなくなること"],
        ["音声だけ削除", "文字起こし、議事録、編集履歴", "再生、再文字起こし、再話者分離"],
        ["会議全体を削除", "アプリ内には残らない", "復元を含むすべて"],
    ], colWidths=[36 * mm, 67 * mm, 58 * mm])
    delete_table.setStyle(TableStyle([
        ("FONTNAME",(0,0),(-1,-1),"JP"), ("FONTNAME",(0,0),(-1,0),"JP-Bold"),
        ("FONTSIZE",(0,0),(-1,-1),8.5), ("BACKGROUND",(0,0),(-1,0),GREEN_DARK),
        ("TEXTCOLOR",(0,0),(-1,0),WHITE), ("BACKGROUND",(0,1),(-1,-1),SURFACE),
        ("GRID",(0,0),(-1,-1),0.5,LINE), ("VALIGN",(0,0),(-1,-1),"TOP"),
        ("TOPPADDING",(0,0),(-1,-1),6), ("BOTTOMPADDING",(0,0),(-1,-1),6),
    ]))
    story += [delete_table, Spacer(1, 7 * mm)]
    story += [h2("用語", styles)]
    terms = [
        ("文字起こし", "音声を文章へ変換したもの"),
        ("話者分離", "発話を話者A、話者Bなどへ分ける処理"),
        ("テキスト整形", "文字起こしを読みやすく整える処理"),
        ("議事録", "目的、議題、決定事項、対応項目などを整理したもの"),
        ("Provider", "ローカル、OpenAI、GeminiなどのAI処理提供元"),
        ("暗号化バックアップ", "パスフレーズで保護された復元用ファイル"),
    ]
    term_table = Table(terms, colWidths=[40 * mm, 120 * mm])
    term_table.setStyle(TableStyle([
        ("FONTNAME",(0,0),(-1,-1),"JP"), ("FONTNAME",(0,0),(0,-1),"JP-Bold"),
        ("FONTSIZE",(0,0),(-1,-1),8.8), ("LINEBELOW",(0,0),(-1,-1),0.5,LINE),
        ("TOPPADDING",(0,0),(-1,-1),6), ("BOTTOMPADDING",(0,0),(-1,-1),6),
    ]))
    story += [term_table, Spacer(1, 9 * mm)]
    story += [callout("より詳しい文章版は、同梱の「mint-ai-recorder-user-manual.md」を参照してください。", styles)]

    doc.build(story, onFirstPage=on_page, onLaterPages=on_page)
    print(OUT)


if __name__ == "__main__":
    make_pdf()
