#!/usr/bin/env python3
"""Read a uiautomator dump and answer questions about rows and their switches.

Usage (XML on stdin):
    uinodes.py rows                 every row label on screen
    uinodes.py switch "<label>"     that row's switch: "on|off enabled|disabled" (or "missing")
    uinodes.py tap "<label>"        x y to tap the row's LABEL
    uinodes.py tapswitch "<label>"  x y to tap the row's SWITCH

Matching a switch to its label by document order was the flaky part: a row's Switch is not reliably
the next checkable node, and a screen with three toggles would hand back the wrong one. This pairs
them GEOMETRICALLY — the checkable node whose vertical centre is nearest the label's, and which
overlaps it vertically — which is what "the switch on this row" actually means on screen.
"""
import re
import sys


def nodes(xml):
    out = []
    for n in re.findall(r'<node[^>]*/?>', xml):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not b:
            continue
        x1, y1, x2, y2 = (int(g) for g in b.groups())
        out.append({
            "text": (re.search(r'\btext="([^"]*)"', n) or [None, ""])[1],
            "desc": (re.search(r'content-desc="([^"]*)"', n) or [None, ""])[1],
            "checkable": 'checkable="true"' in n,
            "checked": 'checked="true"' in n,
            "enabled": 'enabled="true"' in n,
            "box": (x1, y1, x2, y2),
            "cy": (y1 + y2) // 2,
            "cx": (x1 + x2) // 2,
        })
    return out


# A switch may sit slightly above its label's centre on a tight row.
LABEL_TOLERANCE_PX = 40
# Furthest a switch was measured from its own label (a wrapped two-line label): 237 px.
ROW_HEIGHT_PX = 350


# Below this length a label is too generic to match on substring. "OK" matched card body text on the
# Home screen and tapped the status card's action, which opens the system Developer options page — an
# automation harness must never wander into system settings by accident.
MIN_PARTIAL_MATCH_LEN = 8


def find_label(ns, label):
    exact = [n for n in ns if n["text"] == label]
    if exact:
        return exact[0]
    if len(label) < MIN_PARTIAL_MATCH_LEN:
        return None
    part = [n for n in ns if label.lower() in n["text"].lower() and n["text"]]
    return part[0] if part else None


def row_switch(ns, label):
    lab = find_label(ns, label)
    if not lab:
        return None, None
    switches = [n for n in ns if n["checkable"]]
    if not switches:
        return lab, None
    # Measured layout on this app's settings rows: the Switch sits BELOW its label — by 67 px on a
    # one-line row and up to ~240 px where the label wraps — and the row's description sits below the
    # switch again. So "nearest by absolute distance" is wrong: it happily grabs the switch of the row
    # ABOVE. Take the first switch at or below the label instead, within one row's height.
    below = [s for s in switches if s["cy"] >= lab["cy"] - LABEL_TOLERANCE_PX]
    if not below:
        return lab, None
    best = min(below, key=lambda s: s["cy"])
    if best["cy"] - lab["cy"] > ROW_HEIGHT_PX:
        return lab, None
    return lab, best


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "rows"
    xml = sys.stdin.read()
    ns = nodes(xml)

    if cmd == "pairs":
        # Every switch on screen with the label it belongs to: the label is the nearest text ABOVE it.
        labels = [n for n in ns if len(n["text"]) >= 3 and not n["checkable"]]
        for sw in [n for n in ns if n["checkable"]]:
            above = [l for l in labels if l["cy"] <= sw["cy"] + LABEL_TOLERANCE_PX]
            owner = max(above, key=lambda l: l["cy"])["text"] if above else "?"
            print("%-4s %-9s %s" % ("on" if sw["checked"] else "off",
                                    "enabled" if sw["enabled"] else "DISABLED",
                                    owner[:52]))
        return

    if cmd == "rows":
        seen = set()
        for n in ns:
            t = n["text"]
            if len(t) >= 3 and t not in seen:
                seen.add(t)
                print(t)
        return

    label = sys.argv[2]
    lab, sw = row_switch(ns, label)

    if cmd == "switch":
        if lab is None:
            print("missing")
        elif sw is None:
            print("no-switch")
        else:
            print("%s %s" % ("on" if sw["checked"] else "off",
                             "enabled" if sw["enabled"] else "disabled"))
    elif cmd == "tap":
        print("%d %d" % (lab["cx"], lab["cy"]) if lab else "")
    elif cmd == "tapswitch":
        print("%d %d" % (sw["cx"], sw["cy"]) if sw else "")


main()
