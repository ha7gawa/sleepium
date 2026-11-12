"""The front page's charts (art/gallery/): horizontal bar pairs, the reference in gray and Sleepium in the accent (the
emphasis form: one series is the point), each bar labelled with its value, one scale per chart.
    python tools/gen_charts.py [file.png ...]   (only the named charts; all without arguments)
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'art', 'gallery')
FONTS = r'C:\Windows\Fonts'
BG, INK, MUTED, GRID = (20, 24, 46), (236, 239, 255), (150, 160, 200), (52, 58, 92)
REF, ACCENT = (128, 136, 168), (201, 133, 0)   # #8088a8 de-emphasis, #c98500 accent (validated on #14182e)
PREV = (135, 92, 34)   # #875c22 the previous release: a dim accent (all pairs >= 15 normal / CVD; 2.99:1, every bar labelled)
ONLY = sys.argv[1:]


def font(size, bold=False):
    for n in (['segoeuib.ttf', 'arialbd.ttf'] if bold else ['segoeui.ttf', 'arial.ttf']):
        p = os.path.join(FONTS, n)
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def nice_max(v):
    """the smallest round axis end at or above v, with its grid step (4 to 6 steps)"""
    best = None
    for step in (1, 2, 2.5, 5):
        for mag in (1, 10, 100, 1000):
            m = step * mag
            for n in (4, 5, 6):
                if m * n >= v and (best is None or m * n < best[0]):
                    best = (m * n, m)
    return best


def chart(name, title, subtitle, rows, labels, unit, note=None, label_w=520):
    """rows: (label, ref value, sleepium value, ref text, sleepium text)"""
    if ONLY and name not in ONLY:
        return
    W = 1600
    row_h = 112
    top = 230
    H = top + row_h * len(rows) + (110 if note else 70)
    img = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(img)
    d.text((60, 40), title, font=font(44, True), fill=INK)
    d.text((60, 102), subtitle, font=font(26), fill=MUTED)
    # legend: a swatch beside each name, the text in ink
    lx = 60
    for col, txt in ((REF, labels[0]), (ACCENT, labels[1])):
        d.rounded_rectangle([lx, 160, lx + 28, 184], 4, fill=col)
        d.text((lx + 40, 154), txt, font=font(26), fill=INK)
        lx += 40 + d.textlength(txt, font=font(26)) + 60
    left, right = label_w, W - 200
    vmax, step = nice_max(max(max(r[1], r[2]) for r in rows))
    scale = (right - left) / vmax
    y0, y1 = top - 14, top + row_h * len(rows) - 10
    t = 0.0
    while t <= vmax + 1e-9:
        gx = left + t * scale
        d.line([(gx, y0), (gx, y1)], fill=GRID, width=2)
        s = ('%g' % t)
        d.text((gx - d.textlength(s, font=font(22)) / 2, y1 + 8), s, font=font(22), fill=MUTED)
        t += step
    d.text((right + 50, y1 + 8), unit, font=font(22), fill=MUTED)
    for i, (lab, a, b, at, bt) in enumerate(rows):
        y = top + i * row_h
        d.text((60, y + 22), lab, font=font(28, True), fill=INK)
        for j, (v, txt, col) in enumerate(((a, at, REF), (b, bt, ACCENT))):
            by = y + 6 + j * 44
            x1 = left + max(v * scale, 6)
            d.rounded_rectangle([left, by, x1, by + 36], 4, fill=col)   # 4px rounded end, 2px gap between the pair
            d.rectangle([left, by, left + 4, by + 36], fill=col)          # square at the baseline
            d.text((x1 + 14, by + 2), txt, font=font(26, True), fill=INK)
    if note:
        d.text((60, H - 70), note, font=font(22), fill=MUTED)
    img.save(os.path.join(OUT, name))
    print(name, img.size)


chart('forge-1201-n100.png', 'Minecraft 1.20.1 / Forge on an Intel N100 server',
      'milliseconds per tick from vanilla\'s /tick query, production server, lower is better',
      [('4 000 item stacks', 114.9, 10.7, '114.9', '10.7'), ('120 villagers', 19.3, 14.0, '19.3', '14.0'),
       ('300 animals + 50 villagers', 22.2, 13.25, '22.2', '13.25'), ('1 024 idle hoppers', 6.68, 2.4, '6.68', '2.4')],
      ('vanilla (the same jar, optimisations off)', 'Sleepium 1.1.0'), 'ms')

chart('cursedfate-fight.png', 'A Cursed Fate fight on the client (Minecraft 1.20.1)',
      '840 entities, zombies burning at noon, the set\'s effects; frames per second, higher is better; RTX 4070 SUPER',
      [('no other mods', 87, 623, '87', '600-646'), ('with Oculus + Embeddium', 289, 299, '289', '299')],
      ('without Sleepium', 'Sleepium 1.1.0'), 'fps',
      note='Bars at the middle of each measured range. Next to Oculus the entities stay on the render thread (its per-entity state).')

chart('packs-110.png', 'In modpacks: without Sleepium and with 1.1.0',
      'production clients, frames per second, higher is better; RTX 4070 SUPER; EMF: 144 Fresh Animations mobs among 450 entities',
      [('Biohazard, 473 mods (1.20.1)', 369, 575, '351-379', '571-578'),
       ('EMF 3.2 + Fresh Animations (1.20.1)', 32, 391, '32-33', '391-392'),
       ('EMF 3.3 + Fresh Animations (1.21.1)', 44, 498, '42-46', '490-506')],
      ('without Sleepium (the same jars, optimisations off)', 'Sleepium 1.1.0'), 'fps', label_w=600,
      note="The mob scenes: each version's saved test world plus the mobs, so they differ between the versions.")

chart('next-to-mods-110.png', 'Next to other optimisation mods: what 1.1.0 brings back',
      'client_entities (armour stands, item frames, chests, signs, mobs); EMF: 144 Fresh Animations mobs among 450 entities; fps',
      [('Embeddium (1.20.1)', 137, 845, '137', '845'), ('Oculus + Embeddium (1.20.1)', 238, 452, '238', '440-464'),
       ('ImmediatelyFast (1.20.1)', 371, 681, '371', '681'), ('Iris + Sodium, no pack (1.21.1)', 377, 570, '377', '570'),
       ('ImmediatelyFast (1.21.1)', 809, 1149, '809', '1 149'),
       ('EMF 3.2 + Fresh Animations (1.20.1)', 186, 391, '184-187', '391-392'),
       ('EMF 3.3 + Fresh Animations (1.21.1)', 372, 498, '335-408', '490-506')],
      ('Sleepium before 1.1.0, with the same mod', 'Sleepium 1.1.0'), 'fps', label_w=600,
      note='Same machine; frames as before (EMF parts drawn from VRAM: to float rounding). Server: items next to Lithium 4.32 -> 3.37 ms/tick.')


def multi(name, title, subtitle, series, sections, note=None, label_w=560):
    """series: (legend text, colour) top to bottom in each row; sections: (heading or None, unit, rows), each section its
    own scale and axis (small multiples); rows: (label, [value or None per series], [text per series])"""
    if ONLY and name not in ONLY:
        return
    W, bar, gap = 1600, 32, 6
    n = len(series)
    row_h = n * (bar + gap) + 30
    H = 210
    for head, unit, rows in sections:
        H += (52 if head else 0) + row_h * len(rows) + 56
    H += 80 if note else 30
    img = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(img)
    d.text((60, 40), title, font=font(44, True), fill=INK)
    d.text((60, 102), subtitle, font=font(26), fill=MUTED)
    lx = 60
    for txt, col in series:
        d.rounded_rectangle([lx, 160, lx + 28, 184], 4, fill=col)
        d.text((lx + 40, 154), txt, font=font(26), fill=INK)
        lx += 40 + d.textlength(txt, font=font(26)) + 60
    y = 230
    left, right = label_w, W - 260
    for head, unit, rows in sections:
        if head:
            d.text((60, y), head, font=font(28), fill=MUTED)
            y += 52
        vmax, step = nice_max(max(v for r in rows for v in r[1] if v is not None))
        scale = (right - left) / vmax
        y0, y1 = y - 12, y + row_h * len(rows) - 18
        t = 0.0
        while t <= vmax + 1e-9:
            gx = left + t * scale
            d.line([(gx, y0), (gx, y1)], fill=GRID, width=2)
            sv = '{:,}'.format(int(t)).replace(',', ' ') if t == int(t) else '%g' % t
            d.text((gx - d.textlength(sv, font=font(22)) / 2, y1 + 8), sv, font=font(22), fill=MUTED)
            t += step
        d.text((right + 50, y1 + 8), unit, font=font(22), fill=MUTED)
        for lab, vals, texts in rows:
            d.text((60, y + (n * (bar + gap)) / 2 - 20), lab, font=font(28, True), fill=INK)
            for j, ((_, col), v, txt) in enumerate(zip(series, vals, texts)):
                by = y + j * (bar + gap)
                if v is None:
                    d.text((left + 14, by + 1), txt or '-', font=font(24), fill=MUTED)
                    continue
                x1 = left + max(v * scale, 6)
                d.rounded_rectangle([left, by, x1, by + bar], 4, fill=col)   # 4px rounded end
                d.rectangle([left, by, left + 4, by + bar], fill=col)          # square at the baseline
                d.text((x1 + 14, by), txt, font=font(26, True), fill=INK)
            y += row_h
        y += 56
    if note:
        d.text((60, H - 70), note, font=font(22), fill=MUTED)
    img.save(os.path.join(OUT, name))
    print(name, img.size)


S3 = (('without Sleepium (the same jars, optimisations off)', REF), ('Sleepium 1.1.11', PREV), ('Sleepium 1.2.0', ACCENT))

multi('n100-120.png', 'An Intel N100 laptop with Intel graphics',
      'Minecraft 1.21.1, production client, frames per second, higher is better; Intel UHD Graphics',
      S3, [(None, 'fps', [
          ('Terrain view', [88, 81.5, 119], ['88', '81-82', '118-120']),
          ('Terrain, experimental GPU terrain', [88, None, 182.5], ['88', 'not in 1.1.11', '181-184']),
          ('Armour stands, frames, chests, signs', [39, 109.5, 134.5], ['36-42', '109-110', '134-135']),
          ('A pile of dropped items', [52.5, 140.5, 182], ['49-56', '140-141', '182']),
          ('A horde of mobs', [115.5, 139.5, 170.5], ['112-119', '137-142', '161-180'])])],
      note='Bars at the middle of each measured range. GPU terrain is off by default (Options -> Sleepium -> Terrain).', label_w=600)

multi('forge-1201-120.png', 'Minecraft 1.20.1 / Forge: mobs with Entity Model Features',
      'production client, frames per second, higher is better; RTX 4070 SUPER; EMF 3.3.12 + ETF 7.2 + Fresh Animations',
      S3, [('A crowd: scale up to 50 fps', 'fps', [
          ('1 440 mobs', [11, 25, 43], ['11', '25', '43'])]),
          ('Fewer mobs and a modpack: scale up to 600 fps', 'fps', [
          ('144 mobs', [42, 541, 594], ['42', '541', '594']),
          ('Biohazard, 474 mods', [428.5, 469, 471.5], ['427-430', '466-472', '461-482'])])],
      note='Biohazard ships EMF 3.2.4: its mobs stay on the main thread until 1.2.1. Bars at the middle of each range.', label_w=600)

multi('desktop-120.png', 'Without Sleepium and with 1.2.0',
      'production clients, Minecraft 1.21.1; Ryzen 7 5800X + RTX 4070 SUPER unless noted; higher is better',
      (('without Sleepium (the same jars, optimisations off)', REF), ('Sleepium 1.2.0', ACCENT)),
      [('Frames per second', 'fps', [
          ('Terrain view', [332, 3739], ['332', '3 739']),
          ('Armour stands, frames, chests, signs', [69, 2152], ['69', '2 152']),
          ('60 working IE crushers', [177, 1786], ['177', '1 786']),
          ('500 crushers with particles', [265, 2103], ['265', '2 103']),
          ('500 crushers piling ~2 000 items', [32, 423], ['32  (p99 94 ms)', '423  (p99 15 ms)'])]),
       ('World generation, chunks per second', 'chunks/s', [
          ('Desktop', [54, 107], ['54', '107']),
          ('Intel N100', [13, 35], ['13', '35'])])],
      note='IE: Immersive Engineering. p99: the slowest 1 % of frames.', label_w=600)
