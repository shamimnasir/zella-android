#!/usr/bin/env python3
"""Copies the pure planning functions out of the iPhone's EditorViewModel (an app target this build
cannot compile) into one enum, so plans.swift can run the iPhone's own decisions.
Usage: extract-plans.py <EditorViewModel.swift> <out.swift>"""
import re, sys

src = open(sys.argv[1]).read()
names = ['subtractingWitnessWords', 'interruptPlan', 'emphasisZoomStarts', 'emphasisRank', 'smartZoomScale',
         'autoCutSoundPlan', 'autoCutSound', 'autoCutSoundPriority', 'autoCutSoundBudget', 'bundleMusicMood', 'tracksAreCalm']
consts = ['emphasisZoomMinGap', 'emphasisTransitionClearance', 'emphasisOpeningExclusion', 'emphasisZoomLead', 'emphasisZoomSpan',
          'interruptMinWindow', 'interruptCap', 'interruptScale', 'interruptSpan', 'interruptAnchors', 'autoCutSoundVolume',
          'autoCutRiserVolume', 'autoCutMinSpacing', 'autoCutMaxRisers', 'autoCutRiserPauseLength', 'autoCutSecondsPerSound', 'autoCutMaxSounds']
out = ['import Foundation', 'import ZellaCore', '', 'enum Plans {']

def body(start):
    i = src.index('{', start)
    d, j = 0, i
    while True:
        if src[j] == '{':
            d += 1
        elif src[j] == '}':
            d -= 1
            if d == 0:
                return src[start:j + 1]
        j += 1

for n in consts:
    m = re.search(r'nonisolated static let ' + n + r'\b[^\n]*(\n\s+\[[^\n]*\])?', src) or re.search(r'static let ' + n + r'\b[^\n]*', src)
    if not m:
        raise SystemExit('const? ' + n)
    out.append('    ' + re.sub(r'//.*', '', m.group(0)).replace('nonisolated ', ''))
for n in names:
    m = re.search(r'(nonisolated )?static func ' + n + r'\b', src)
    if not m:
        raise SystemExit('func? ' + n)
    text = body(m.start()).replace('nonisolated ', '').replace('EditorViewModel.', 'Plans.')
    out.append('    ' + text)
out.append('}')
open(sys.argv[2], 'w').write('\n'.join(out) + '\n')
print('extracted', len(names), 'functions and', len(consts), 'constants')
