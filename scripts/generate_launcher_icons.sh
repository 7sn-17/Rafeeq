#!/usr/bin/env bash
#
# generate_launcher_icons.sh
#
# Builds every Rafeeq launcher icon from one source picture:
#
#   legacy bitmaps    app/src/main/res/mipmap-<density>/ic_launcher.png
#                     (48, 72, 96, 144, 192 px)
#   adaptive layers   app/src/main/res/drawable-<density>/ic_launcher_foreground.png
#                     (108, 162, 216, 324, 432 px on a 108dp canvas)
#
# The artwork inside the source is detected automatically (the source only has to be
# an opaque picture whose subject sits on a reasonably uniform background), then it is
# cropped, padded to a square with its own background colour and scaled for each density.
#
# Requirements: netpbm  —  Termux: pkg install netpbm
#
# Usage:
#   scripts/generate_launcher_icons.sh [source-image]
#
# Environment overrides:
#   CONTENT_MARGIN  crop side = content side * CONTENT_MARGIN   (default 1.16)
#   ARTWORK_DP      artwork size on the 108dp adaptive canvas   (default 64)
#   THRESHOLD       background/artwork split, 0..1              (default 0.88)
#   BACKGROUND      colour behind the artwork, #rrggbb          (default: sampled)
#   FILTER          pamscale resampling filter                  (default: pixel mixing)
#   LEGACY_SPEC / FOREGROUND_SPEC   "<density>:<pixels>" lists
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
RES_DIR="$PROJECT_DIR/app/src/main/res"

SOURCE="${1:-${SOURCE:-$PROJECT_DIR/rafeeq_icon.png}}"
CONTENT_MARGIN="${CONTENT_MARGIN:-1.16}"
CANVAS_DP="${CANVAS_DP:-108}"
ARTWORK_DP="${ARTWORK_DP:-64}"
THRESHOLD="${THRESHOLD:-0.88}"
BACKGROUND="${BACKGROUND:-}"
FILTER="${FILTER:-}"
LEGACY_SPEC="${LEGACY_SPEC:-mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192}"
FOREGROUND_SPEC="${FOREGROUND_SPEC:-mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432}"

log()  { printf '%s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

# --- dependencies ----------------------------------------------------------
for tool in jpegtopnm pngtopnm ppmtopgm pamthreshold pnmcrop pamcut pamscale \
            ppmmake pnmcat pnmtopng pamfile pnmtoplainpnm od awk; do
    command -v "$tool" >/dev/null 2>&1 \
        || die "required tool '$tool' not found — install netpbm (Termux: pkg install netpbm)"
done

[ -f "$SOURCE" ] || die "source image not found: $SOURCE"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/launcher-icons.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

# Prints "W H" of the image file $1. pamfile only reads the header, so the producer
# must never be on the other end of a pipe (it would be killed by SIGPIPE).
dims_file() { pamfile "$1" | grep -oE '[0-9]+ by [0-9]+' | awk 'NR == 1 {print $1, $3}'; }

# Averages a PPM on stdin down to one pixel and prints it as #rrggbb.
average_colour() {
    pamscale -width 1 -height 1 | pnmtoplainpnm | tail -1 \
        | awk '{printf "#%02x%02x%02x", $1, $2, $3}'
}

# --- 1. decode the source --------------------------------------------------
step "Reading $SOURCE"
magic="$(od -An -tx1 -N8 "$SOURCE" | tr -d ' \n')"
case "$magic" in
    89504e470d0a1a0a*)  decoder=pngtopnm ;;
    ffd8ff*)            decoder=jpegtopnm ;;
    *) die "unsupported source format (magic bytes '$magic'); expected PNG or JPEG" ;;
esac
# The file name is deliberately ignored: the format is taken from the content.
log "  detected $( [ "$decoder" = pngtopnm ] && echo PNG || echo JPEG ) (file is named $(basename "$SOURCE"))"

"$decoder" "$SOURCE" > "$TMP/source.pnm" 2>/dev/null
read -r SRC_W SRC_H <<<"$(dims_file "$TMP/source.pnm")" || true
SRC_W="${SRC_W:-0}"; SRC_H="${SRC_H:-0}"
[ "${SRC_W}" -gt 0 ] && [ "${SRC_H}" -gt 0 ] || die "could not read the source image"
log "  source size: ${SRC_W}x${SRC_H}"

# --- 2. locate the artwork -------------------------------------------------
step "Detecting the artwork"
ppmtopgm "$TMP/source.pnm" | pamthreshold -simple -threshold="$THRESHOLD" > "$TMP/mask.pbm"

content_w=0; content_h=0; content_left=0; content_top=0
for polarity in -white -black; do
    pnmcrop "$polarity" "$TMP/mask.pbm" > "$TMP/content.pnm"
    w=0; h=0
    read -r w h <<<"$(dims_file "$TMP/content.pnm")" || true
    w="${w:-0}"; h="${h:-0}"
    # Skip this polarity when nothing (or, more likely, everything) was cropped.
    [ "$w" -gt 0 ] && [ "$h" -gt 0 ] || continue
    [ "$w" -ge $((SRC_W * 98 / 100)) ] && [ "$h" -ge $((SRC_H * 98 / 100)) ] && continue

    pnmcrop "$polarity" -left "$TMP/mask.pbm" > "$TMP/no_left.pnm"
    pnmcrop "$polarity" -top  "$TMP/mask.pbm" > "$TMP/no_top.pnm"
    keep_w="$(dims_file "$TMP/no_left.pnm" | awk '{print $1}' || true)"
    keep_h="$(dims_file "$TMP/no_top.pnm"  | awk '{print $2}' || true)"
    content_w=$w; content_h=$h
    content_left=$(( SRC_W - ${keep_w:-0} ))
    content_top=$(( SRC_H - ${keep_h:-0} ))
    log "  found artwork with '$polarity'"
    break
done

if [ "$content_w" -le 0 ] || [ "$content_h" -le 0 ]; then
    log "  no distinct artwork detected — falling back to the whole picture"
    content_w=$SRC_W; content_h=$SRC_H; content_left=0; content_top=0
fi
# Keep the geometry sane even if the detection came back out of range.
[ "$content_left" -lt 0 ] || [ "$content_left" -ge "$SRC_W" ] && content_left=0
[ "$content_top"  -lt 0 ] || [ "$content_top"  -ge "$SRC_H" ] && content_top=0
log "  artwork: ${content_w}x${content_h} at (${content_left},${content_top})"

# --- 3. crop a square around the artwork -----------------------------------
step "Cropping"
# Square crop centred on the artwork; the part that would fall outside the source
# is added back as padding, so the artwork always stays centred.
read -r side cut_x cut_y <<<"$(awk -v cw="$content_w" -v ch="$content_h" \
    -v l="$content_left" -v t="$content_top" -v m="$CONTENT_MARGIN" 'BEGIN {
        s = int((cw > ch ? cw : ch) * m + 0.999);
        if (s % 2) s++;
        printf "%d %d %d", s, int(l + cw / 2 - s / 2 + 0.5), int(t + ch / 2 - s / 2 + 0.5);
    }')"
read -r ix iy iw ih pad_l pad_t <<<"$(awk -v s="$side" -v x="$cut_x" -v y="$cut_y" \
    -v W="$SRC_W" -v H="$SRC_H" 'BEGIN {
        x0 = x < 0 ? 0 : x;       y0 = y < 0 ? 0 : y;
        x1 = x + s > W ? W : x + s; y1 = y + s > H ? H : y + s;
        printf "%d %d %d %d %d %d", x0, y0, x1 - x0, y1 - y0, x0 - x, y0 - y;
    }')"
pad_r=$(( side - pad_l - iw ))
pad_b=$(( side - pad_t - ih ))
log "  crop ${side}x${side} (artwork padded by ${pad_l}/${pad_t}/${pad_r}/${pad_b})"

pamcut -left "$ix" -top "$iy" -width "$iw" -height "$ih" "$TMP/source.pnm" > "$TMP/cut.pnm"

# --- 4. background colour --------------------------------------------------
if [ -z "$BACKGROUND" ]; then
    # Average of the four corners of the crop: the artwork's own surround.
    patch=16; inner=2
    pamcut -left "$inner" -top "$inner" -width "$patch" -height "$patch" "$TMP/cut.pnm" > "$TMP/c1.pnm"
    pamcut -left $((iw - patch - inner)) -top "$inner" -width "$patch" -height "$patch" "$TMP/cut.pnm" > "$TMP/c2.pnm"
    pamcut -left "$inner" -top $((ih - patch - inner)) -width "$patch" -height "$patch" "$TMP/cut.pnm" > "$TMP/c3.pnm"
    pamcut -left $((iw - patch - inner)) -top $((ih - patch - inner)) -width "$patch" -height "$patch" "$TMP/cut.pnm" > "$TMP/c4.pnm"
    BACKGROUND="$(pnmcat -tb "$TMP/c1.pnm" "$TMP/c2.pnm" "$TMP/c3.pnm" "$TMP/c4.pnm" | average_colour)"
fi
log "  background colour: $BACKGROUND"

# --- 5. pad the crop to a square -------------------------------------------
h_parts=( "$TMP/cut.pnm" )
if [ "$pad_l" -gt 0 ]; then ppmmake "$BACKGROUND" "$pad_l" "$ih" > "$TMP/pad_l.pnm"; h_parts=( "$TMP/pad_l.pnm" "${h_parts[@]}" ); fi
if [ "$pad_r" -gt 0 ]; then ppmmake "$BACKGROUND" "$pad_r" "$ih" > "$TMP/pad_r.pnm"; h_parts+=( "$TMP/pad_r.pnm" ); fi
pnmcat -lr "${h_parts[@]}" > "$TMP/row.pnm"

v_parts=( "$TMP/row.pnm" )
if [ "$pad_t" -gt 0 ]; then ppmmake "$BACKGROUND" "$side" "$pad_t" > "$TMP/pad_t.pnm"; v_parts=( "$TMP/pad_t.pnm" "${v_parts[@]}" ); fi
if [ "$pad_b" -gt 0 ]; then ppmmake "$BACKGROUND" "$side" "$pad_b" > "$TMP/pad_b.pnm"; v_parts+=( "$TMP/pad_b.pnm" ); fi
pnmcat -tb "${v_parts[@]}" > "$TMP/square.pnm"

square_w=0; square_h=0
read -r square_w square_h <<<"$(dims_file "$TMP/square.pnm")" || true
[ "${square_w:-0}" -eq "$side" ] && [ "${square_h:-0}" -eq "$side" ] \
    || die "internal error: square crop is ${square_w:-?}x${square_h:-?}, expected ${side}x${side}"

scale_filter=()
[ -n "$FILTER" ] && scale_filter=( "-filter=$FILTER" )

# --- 6. legacy launcher bitmaps -------------------------------------------
step "Legacy icons (mipmap-*)"
for spec in $LEGACY_SPEC; do
    density="${spec%%:*}"; px="${spec##*:}"
    mkdir -p "$RES_DIR/mipmap-$density"
    out="$RES_DIR/mipmap-$density/ic_launcher.png"
    pamscale "${scale_filter[@]}" -width "$px" -height "$px" "$TMP/square.pnm" | pnmtopng > "$out"
    log "  ${px}x${px}  mipmap-$density/ic_launcher.png"
done

# --- 7. adaptive foreground layers ----------------------------------------
step "Adaptive foreground (drawable-*)"
for spec in $FOREGROUND_SPEC; do
    density="${spec%%:*}"; canvas="${spec##*:}"
    art=$(( canvas * ARTWORK_DP / CANVAS_DP ))
    pad_x=$(( (canvas - art) / 2 )); pad_x2=$(( canvas - art - pad_x ))
    mkdir -p "$RES_DIR/drawable-$density"
    out="$RES_DIR/drawable-$density/ic_launcher_foreground.png"

    pamscale "${scale_filter[@]}" -width "$art" -height "$art" "$TMP/square.pnm" > "$TMP/art.pnm"
    ppmmake "$BACKGROUND" "$pad_x"  "$art" > "$TMP/fl.pnm"
    ppmmake "$BACKGROUND" "$pad_x2" "$art" > "$TMP/fr.pnm"
    pnmcat -lr "$TMP/fl.pnm" "$TMP/art.pnm" "$TMP/fr.pnm" > "$TMP/frow.pnm"
    ppmmake "$BACKGROUND" "$canvas" "$pad_x"  > "$TMP/ft.pnm"
    ppmmake "$BACKGROUND" "$canvas" "$pad_x2" > "$TMP/fb.pnm"
    pnmcat -tb "$TMP/ft.pnm" "$TMP/frow.pnm" "$TMP/fb.pnm" | pnmtopng > "$out"
    log "  ${canvas}x${canvas}  drawable-$density/ic_launcher_foreground.png (artwork ${art}px)"
done

# --- done -----------------------------------------------------------------
step "Generated"
find "$RES_DIR" -name 'ic_launcher*.png' | sort | while read -r f; do
    printf '  %-64s %6s bytes\n' "${f#"$RES_DIR"/}" "$(wc -c < "$f")"
done
log ""
log "Remember to keep app/src/main/res/values/colors.xml in sync:"
log "  <color name=\"ic_launcher_background\">$BACKGROUND</color>"
