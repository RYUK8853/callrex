#!/usr/bin/env bash
# Reproduces the 2026-09-02 measurement behind the merge plan: a stream-copy
# concatenation preserves each part's encoded frames, so un-merging is a
# frame-exact cut rather than a re-encode.
#
# Needs ffmpeg. Uses synthetic tones — never a real recording.
set -euo pipefail
d=$(mktemp -d); trap 'rm -rf "$d"' EXIT; cd "$d"

ffmpeg -v error -f lavfi -i "sine=frequency=440:duration=5:sample_rate=48000" -ac 1 -c:a aac -b:a 64k A.m4a
ffmpeg -v error -f lavfi -i "sine=frequency=880:duration=3:sample_rate=48000" -ac 1 -c:a aac -b:a 64k B.m4a
printf "file '%s'\n" A.m4a B.m4a > list.txt
ffmpeg -v error -f concat -safe 0 -i list.txt -c copy M.m4a

for f in A B M; do ffmpeg -v error -i $f.m4a -c copy -f adts $f.aac; done
a=$(stat -f%z A.aac); b=$(stat -f%z B.aac); m=$(stat -f%z M.aac)
echo "frame bytes: A=$a B=$b sum=$((a+b)) merged=$m"
head -c "$a" M.aac | cmp - A.aac && echo "part A frames: IDENTICAL"
tail -c "$b" M.aac | cmp - B.aac && echo "part B frames: IDENTICAL"

ffmpeg -v error -i A.m4a -f s16le -acodec pcm_s16le A.pcm
ffmpeg -v error -i M.m4a -f s16le -acodec pcm_s16le M.pcm
python3 - <<'PY'
import struct
sa=struct.unpack_from(f'<{len(open("A.pcm","rb").read())//2}h', open("A.pcm","rb").read())
mb=open("M.pcm","rb").read(); sb=struct.unpack_from(f'<{len(mb)//2}h', mb)
best=min(((s, sum(abs(sa[i]-sb[i+s]) for i in range(1000,3000)))
          for s in range(0,3001) if s+3000 < len(sb)), key=lambda x: x[1])
sh=best[0]; n=min(len(sa), len(sb)-sh)
bad=sum(1 for i in range(n) if sa[i]!=sb[i+sh])
print(f"alignment offset = {sh} samples ({sh/48000*1000:.2f} ms, the AAC priming delay)")
print(f"decoded PCM differing samples after alignment: {bad} of {n}")
PY
