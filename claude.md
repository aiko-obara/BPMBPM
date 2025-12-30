# MPD218 Pad Training App - Implementation Specification

## 1. Architecture
- Platform: Android Native
- Language: Kotlin
- UI: Jetpack Compose
- Architecture: MVVM
- State Management: Single PracticeState shared via ViewModel

---

## 2. Screen Definitions

### S0: ConnectionScreen
- MPD218 USB MIDI接続確認
- 接続成功時のみ「はじめる」表示
- 未接続時はアニメーション表示のみ

---

### S1: HomeScreen
- 表示要素：大きな「はじめる」ボタン1つ
- 設定・文字説明なし

---

### S2: ModeSelectScreen
- ボタン2つのみ
  - タイミングれんしゅう
  - パッドおぼえる
- 戻るボタンあり

---

### S3: PracticeScreen
- 16パッド表示
- 指示パッドを発光表示
- カウントダウン後に開始
- MIDI入力をリアルタイム処理
- UI表示は ○ △ × のみ

---

### S4: ResultScreen
- ★1〜3 表示
- 効果音＋簡単なアニメーション
- ボタン：
  - つづける
  - もどる

---

## 3. Practice Logic

### Timing Mode
- BPMはレベル依存で自動設定
- タイミング誤差をmsで内部計測
- UIには数値非表示

### Memory Mode
- 指示されたパッド順を内部保持
- 入力順と照合
- 反応時間を内部計測

---

## 4. MIDI Handling
- android.media.midi 使用
- MPD218のNote番号を16パッドに固定マッピング
- Velocity取得必須

---

## 5. Feedback Rules
- 成功：明るい音＋アニメ
- 失敗：短い効果音のみ
- 否定的テキスト禁止

---

## 6. Non-Functional
- 完全オフライン
- レイテンシ最優先
- 対象端末差異を考慮し例外処理必須

---

## 7. Out of Scope
- iOS対応
- 他MIDIデバイス
- アカウント
- 課金
