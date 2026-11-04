#!/usr/bin/env python3
"""Reads the iPhone's title style list out of StudioSheet.swift and writes it as data for Android.
The list lives in the app target, which the macOS conformance build cannot compile.
Usage: title-looks.py <StudioSheet.swift> <out.json>"""
import json, re, sys

src = open(sys.argv[1]).read()
start = src.index('static let styleBundles: [TitleStyleBundle] = [')
i = src.index('[', start + len('static let styleBundles: [TitleStyleBundle] ='))
depth, j = 0, i
while True:
    ch = src[j]
    if ch == '[':
        depth += 1
    elif ch == ']':
        depth -= 1
        if depth == 0:
            break
    j += 1
body = re.sub(r'//[^\n]*', '', src[i + 1:j])

def split_top(s):
    out, d, cur, q = [], 0, '', False
    for c in s:
        if c == '"':
            q = not q
        if not q:
            if c in '([':
                d += 1
            elif c in ')]':
                d -= 1
            if c == ',' and d == 0:
                out.append(cur.strip())
                cur = ''
                continue
        cur += c
    if cur.strip():
        out.append(cur.strip())
    return out

def colour(v):
    v = v.strip()
    m = re.match(r'(.*)\.opacity\(([\d.]+)\)$', v)
    a = 1.0
    if m:
        v, a = m.group(1), float(m.group(2))
    named = {'.white': [1, 1, 1], 'Color.white': [1, 1, 1], '.black': [0, 0, 0], 'Color.black': [0, 0, 0]}
    if v in ('.clear', 'Color.clear'):
        return [0, 0, 0, 0]
    if v in named:
        return named[v] + [a]
    m = re.match(r'Color\(red: ([\d.]+), green: ([\d.]+), blue: ([\d.]+)\)$', v)
    if m:
        return [float(m.group(1)), float(m.group(2)), float(m.group(3)), a]
    raise SystemExit('colour? ' + v)

def rgba(v):
    m = re.match(r'ColorRGBA\(([\d.]+), ([\d.]+), ([\d.]+)\)$', v.strip())
    if not m:
        raise SystemExit('rgba? ' + v)
    return [float(m.group(1)), float(m.group(2)), float(m.group(3)), 1.0]

looks = []
for m in re.finditer(r'\.init\(', body):
    a = m.end()
    d, b = 1, a
    while d:
        if body[b] == '(':
            d += 1
        elif body[b] == ')':
            d -= 1
        b += 1
    o = {}
    for part in split_top(body[a:b - 1]):
        k, v = part.split(':', 1)
        k, v = k.strip(), v.strip()
        if k in ('name', 'label', 'font', 'plate'):
            o[k] = None if v == 'nil' else v.strip('"')
        elif k in ('fill', 'strokeColor', 'boxColor', 'extrudeColor', 'gradient', 'highlightColor'):
            o[k] = None if v == 'nil' else colour(v)
        elif k in ('strokeOn', 'boxOn', 'highlightMiddle', 'card', 'uppercase'):
            o[k] = v == 'true'
        elif k in ('strokeW', 'shadow', 'size', 'rotation', 'extrude'):
            o[k] = float(v)
        elif k == 'entrance':
            o[k] = {'.markerHighlight': 'Marker Highlight', '.underlineDraw': 'Underline Draw', '.boxTrace': 'Box Trace'}.get(v, v.lstrip('.'))
        elif k == 'accent':
            am = re.match(r'TitleAccent\(pick: "(\w+)"(.*)\)$', v)
            acc = {'pick': am.group(1)}
            for f in split_top(am.group(2).lstrip(', ')):
                if not f:
                    continue
                ak, av = f.split(':', 1)
                ak, av = ak.strip(), av.strip()
                if ak == 'scale':
                    acc['scale'] = float(av)
                elif ak == 'fontName':
                    acc['fontName'] = av.strip('"')
                elif ak == 'color':
                    acc['color'] = rgba(av)
            o['accent'] = acc
        else:
            raise SystemExit('field? ' + k)
    looks.append(o)
json.dump(looks, open(sys.argv[2], 'w'), indent=0, sort_keys=True)
print(f'{len(looks)} title looks')
