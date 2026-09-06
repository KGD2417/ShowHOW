"""guide.json -> a robot program a simulator can run.

The guide is a person talking. A simulator wants verbs with arguments. This is
the whole translation layer, and it is deliberately a keyword table rather than
a model: a wrong verb here is a robot arm doing something the expert never did,
and there is no way for a viewer to tell that from a correct one.

Anything it cannot read confidently becomes INSPECT -- the arm moves to look at
the part and does nothing to it. Never a guessed manipulation.

    python tools/sim/guide_to_program.py guides/<id>/guide.json -o program.json
    python tools/sim/guide_to_program.py --selfcheck
"""
import argparse, json, re, sys

# --- 2D -> 3D -------------------------------------------------------------
# The app's detector hands back boxes in normalized image coords (0..1, origin
# top-left; see ai/ObjectDetectSource.DetectionBox). A single camera cannot
# recover depth, so we do the one thing that is actually true on a workbench:
# everything lies on the table plane. Image y becomes depth, image x becomes
# lateral. Calibrate these four numbers to whatever the camera actually framed.
# z is the tabletop of pybullet_data's table/table.urdf plus a part's height.
TABLE = {"x_far": 0.66, "x_near": 0.32, "y_left": -0.26, "y_right": 0.26, "z": 0.65}


def box_to_world(left, top, right, bottom, table=TABLE):
    """Centre of a normalized detection box -> a point on the table plane."""
    cx, cy = (left + right) / 2, (top + bottom) / 2
    x = table["x_far"] - cy * (table["x_far"] - table["x_near"])
    y = table["y_left"] + cx * (table["y_right"] - table["y_left"])
    return [round(x, 4), round(y, 4), table["z"]]


# --- text -> verb ---------------------------------------------------------
# First match wins, so order matters: "screw in" must beat bare "screw".
VERBS = [
    ("unscrew", ("unscrew", "loosen", "undo", "counterclockwise",
                 "anticlockwise", "anti-clockwise", "back out")),
    ("screw",   ("screw in", "screw it in", "tighten", "fasten", "do up",
                 "clockwise")),
    ("pick",    ("lift", "pull", "take out", "take off", "remove", "detach",
                 "disconnect", "unplug", "pick up", "pop off")),
    ("place",   ("place", "insert", "put", "fit", "seat", "slot", "align",
                 "connect", "plug", "attach", "refit", "line up")),
    ("push",    ("press", "push", "click", "snap", "clip")),
]
INSPECT = "inspect"

PARTS = ("philips screwdriver", "screwdriver", "motherboard", "heatsink",
         "ram module", "ram", "battery", "keyboard", "back panel", "panel",
         "screw", "connector", "cable", "ribbon", "ssd", "fan", "laptop")
DEFAULT_PART = "workpiece"


def read_step(text):
    """One instruction -> (verb, part). Unreadable -> ('inspect', ...)."""
    t = " " + re.sub(r"[^a-z0-9 ]+", " ", (text or "").lower()) + " "
    t = re.sub(r"\s+", " ", t)
    verb, after = INSPECT, 0
    for v, keys in VERBS:
        found = [t.find(k) for k in keys if k in t]
        if found:
            verb, after = v, min(found)
            after += max(len(k) for k in keys if t.find(k) == after)
            break
    # Parts are read after the verb phrase -- "screw in the panel" is about the
    # panel -- and the earliest one wins, longest name breaking a tie, so
    # "unscrew the screws on the back panel" is about the screws.
    hits = [(t.find(" " + p, after), -len(p), p) for p in PARTS if t.find(" " + p, after) >= 0]
    if not hits:   # "refit the panel and screw it back in clockwise" -- the
        # verb matched at the end of the sentence, so look in front of it too.
        hits = [(t.find(" " + p), -len(p), p) for p in PARTS if " " + p in t]
    part = min(hits)[2] if hits else DEFAULT_PART
    # Taking a screw out is unscrewing it, whichever word the expert used.
    # "remove the two screws" and "unscrew them" are the same job.
    if part in ("screw", "screws") and verb == "pick":
        verb = "unscrew"
    return verb, part


# Where a part sits when no detection box pinned it down. A fixed bench layout
# beats a random one: the same guide replays identically for the judges.
BENCH = {
    "laptop":     [0.50,  0.00, 0.65],
    "back panel": [0.50,  0.00, 0.68],
    "panel":      [0.50,  0.00, 0.68],
    "screw":      [0.42, -0.14, 0.65],
    "ram":        [0.55,  0.12, 0.65],
    "ram module": [0.55,  0.12, 0.65],
    "battery":    [0.58, -0.10, 0.65],
    "screwdriver":[0.36,  0.22, 0.65],
    "philips screwdriver": [0.36, 0.22, 0.65],
}


def place_part(part, step):
    """Prefer what the camera saw; fall back to the bench layout."""
    for b in step.get("boxes", []):
        if part.split()[-1] in b.get("label", "").lower():
            return box_to_world(b["left"], b["top"], b["right"], b["bottom"]), "detector"
    return BENCH.get(part, [0.50, 0.0, 0.65]), "layout"


def to_program(guide):
    ops, objects = [], {}
    for step in guide.get("steps", []):
        if step.get("aside"):
            continue
        text = step.get("instruction") or step.get("transcript") or step.get("caption", "")
        verb, part = read_step(text)
        # "press it down until the clips snap" names nothing. It means the part
        # the previous step was about, and pretending otherwise puts the arm
        # somewhere the expert never was.
        if part == DEFAULT_PART and ops:
            part = ops[-1]["target"]
        pos, source = place_part(part, step)
        objects.setdefault(part, {"name": part, "pos": pos, "source": source})
        ops.append({
            "step": step.get("index", len(ops)),
            "op": verb,
            "target": part,
            "pos": pos,
            "say": text.strip()[:120],
            "warning": step.get("warning"),
        })
    return {
        "title": guide.get("title", "guide"),
        "objects": list(objects.values()),
        "ops": ops,
    }


def selfcheck():
    assert read_step("Unscrew the four screws on the back panel") == ("unscrew", "screw")
    assert read_step("Lift the RAM module out of its slot") == ("pick", "ram module")
    assert read_step("Screw in the panel clockwise") == ("screw", "panel")
    assert read_step("Now we talk about warranties") == ("inspect", "workpiece")
    assert read_step("") == ("inspect", "workpiece")
    # top-left of the image is far and left of the arm
    far_left = box_to_world(0.0, 0.0, 0.1, 0.1)
    near_right = box_to_world(0.9, 0.9, 1.0, 1.0)
    assert far_left[0] > near_right[0] and far_left[1] < near_right[1], (far_left, near_right)
    assert read_step("Undo the two screws at the bottom") == ("unscrew", "screw")
    assert read_step("Remove the two screws") == ("unscrew", "screw")
    assert read_step("Refit the panel and screw it back in clockwise") == ("screw", "panel")
    carried = to_program({"steps": [
        {"index": 0, "instruction": "Pull the RAM module out"},
        {"index": 1, "instruction": "Press it down until it clicks"},
    ]})["ops"]
    assert carried[1]["target"] == "ram module", carried
    p = to_program({"steps": [
        {"index": 0, "instruction": "Unscrew the screw", "boxes": [
            {"label": "screw", "left": 0.4, "top": 0.4, "right": 0.6, "bottom": 0.6}]},
        {"index": 1, "instruction": "chit chat", "aside": True},
    ]})
    assert len(p["ops"]) == 1 and p["objects"][0]["source"] == "detector", p
    print("ok")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("guide", nargs="?")
    ap.add_argument("-o", "--out", default="-")
    ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    if a.selfcheck:
        selfcheck(); sys.exit(0)
    prog = to_program(json.load(open(a.guide, encoding="utf-8")))
    text = json.dumps(prog, indent=2)
    if a.out == "-":
        print(text)
    else:
        open(a.out, "w", encoding="utf-8").write(text)
        print(f"{len(prog['ops'])} ops -> {a.out}")
