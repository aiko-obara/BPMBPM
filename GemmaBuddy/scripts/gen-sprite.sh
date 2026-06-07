#!/usr/bin/env bash
#
# gen-sprite.sh — Claude Code 環境から画像生成を呼ぶ仕組み（Codex ブリッジ）
#
# Codex CLI（インストール・認証済み）の image_gen でピクセルアートのスプライトシートを生成し、
# generate2dsprite の processor で透過化＋GIF 書き出しして、指定パス（既定: assets 配下）へ配置する。
#
# 例:
#   scripts/gen-sprite.sh \
#     --name hit_spark --target asset --mode impact \
#     --dest app/src/main/assets/effects/hit_spark.gif \
#     --prompt "retro 8-bit hit spark / impact burst, white-hot core with warm yellow and orange sparks radiating outward, expands then dissipates, chunky pixels, bold dark outline"
#
# 依存: codex, python3(+PIL,requests), generate2dsprite.py(skill)
set -euo pipefail

# ---- 既定値 -------------------------------------------------------------
NAME=""
PROMPT=""
TARGET="asset"          # processor の target: asset|creature|npc|player
MODE="impact"           # target=asset の mode: 例 impact(2x2)
ROWS=2
COLS=2
DEST=""                 # 出力 GIF パス（未指定なら assets/effects/<name>.gif）
COMPONENT_MODE="all"    # FX/エフェクトは all（分離スパークを拾う）。キャラ本体は largest 推奨
MODEL=""                # codex の --model 上書き（任意）

SKILL_DIR="${GEN2D_SKILL_DIR:-$HOME/.claude/skills/generate2dsprite}"
PROCESSOR="$SKILL_DIR/scripts/generate2dsprite.py"
CODEX_IMAGES_DIR="${CODEX_HOME:-$HOME/.codex}/generated_images"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() {
  sed -n '2,18p' "$0"
  exit 1
}

# ---- 引数パース ---------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) NAME="$2"; shift 2 ;;
    --prompt) PROMPT="$2"; shift 2 ;;
    --target) TARGET="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --rows) ROWS="$2"; shift 2 ;;
    --cols) COLS="$2"; shift 2 ;;
    --dest) DEST="$2"; shift 2 ;;
    --component-mode) COMPONENT_MODE="$2"; shift 2 ;;
    --model) MODEL="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown arg: $1" >&2; usage ;;
  esac
done

[[ -z "$NAME" ]] && { echo "ERROR: --name は必須です" >&2; exit 1; }
[[ -z "$PROMPT" ]] && { echo "ERROR: --prompt は必須です" >&2; exit 1; }
[[ -z "$DEST" ]] && DEST="$REPO_ROOT/app/src/main/assets/effects/${NAME}.gif"

command -v codex >/dev/null || { echo "ERROR: codex CLI が見つかりません" >&2; exit 1; }
[[ -f "$PROCESSOR" ]] || { echo "ERROR: processor が見つかりません: $PROCESSOR" >&2; exit 1; }

RUN="$(mktemp -d)"
trap 'rm -rf "$RUN"' EXIT
MARK="$RUN/.start"; touch "$MARK"

# ---- 1) 生成プロンプト（創作指示＋スプライトシート厳格ルール） ----------
FULL_PROMPT="Use the image_gen tool to create exactly ONE pixel-art sprite sheet image and save it.

Subject / direction: ${PROMPT}

Strict layout rules:
- ${ROWS}x${COLS} grid of animation frames (read left-to-right, top-to-bottom), one continuous sequence.
- Retro 8-bit / 16-bit pixel art, chunky pixels, crisp edges, limited palette.
- Solid #FF00FF (magenta) background filling the ENTIRE image (used for keying). No other background.
- The subject stays centered in each cell at consistent scale; nothing crosses cell edges; generous magenta padding.
- No text, no UI, no grid lines, no borders, no drop shadows on the magenta.

After generating, reply with ONLY the absolute file path of the saved PNG."

echo "▶ Codex で生成中… (target=$TARGET mode=$MODE grid=${ROWS}x${COLS})"
# codex exec は非対話。画像生成(image_gen)は ~/.codex/generated_images へ保存される。
# 危険なサンドボックス/承認バイパスは使わない（image_gen はツール呼び出しのため shell サンドボックス対象外）。
CODEX_ARGS=( exec --skip-git-repo-check -C "$RUN" -o "$RUN/last.txt" )
[[ -n "$MODEL" ]] && CODEX_ARGS+=( --model "$MODEL" )
codex "${CODEX_ARGS[@]}" "$FULL_PROMPT" || { echo "ERROR: codex exec 失敗" >&2; exit 1; }

# ---- 2) 生成された raw PNG を特定（マーカーより新しい最新ファイル） -------
RAW=""
if [[ -d "$CODEX_IMAGES_DIR" ]]; then
  RAW="$(find "$CODEX_IMAGES_DIR" -type f -name '*.png' -newer "$MARK" 2>/dev/null | xargs -r ls -t 2>/dev/null | head -1 || true)"
fi
# フォールバック: codex の最終メッセージにパスが書かれていれば拾う
if [[ -z "$RAW" && -f "$RUN/last.txt" ]]; then
  CAND="$(grep -oE '/[^[:space:]]+\.png' "$RUN/last.txt" | tail -1 || true)"
  [[ -n "$CAND" && -f "$CAND" ]] && RAW="$CAND"
fi
[[ -z "$RAW" || ! -f "$RAW" ]] && { echo "ERROR: 生成画像が見つかりません（$CODEX_IMAGES_DIR）" >&2; exit 1; }
echo "✓ raw: $RAW"

# ---- 3) 透過化＋フレーム分割＋GIF 書き出し（processor） ------------------
echo "▶ 透過化・GIF 書き出し中…"
python3 "$PROCESSOR" process \
  --input "$RAW" \
  --target "$TARGET" \
  --mode "$MODE" \
  --rows "$ROWS" --cols "$COLS" \
  --component-mode "$COMPONENT_MODE" \
  --output-dir "$RUN/out" >/dev/null

GIF="$RUN/out/animation.gif"
[[ -f "$GIF" ]] || { echo "ERROR: animation.gif が生成されませんでした" >&2; ls -R "$RUN/out" >&2; exit 1; }

# ---- 4) 配置 ------------------------------------------------------------
mkdir -p "$(dirname "$DEST")"
cp "$GIF" "$DEST"
SIZE="$(du -h "$DEST" | cut -f1)"
echo "✅ 完了: $DEST ($SIZE)"
echo "   透過シート: $RUN/out/sheet-transparent.png（確認用に残すなら別途コピー）"
