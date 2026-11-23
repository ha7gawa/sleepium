"""Mob mass scenes (notes/98): thousands of mobs, the MCRS / Horizon video's case (30,000 pigs) and its relatives.

    python tools/gen_mobmass.py

Writes src/scenes/resources/data/sleep/function/lag_<name>.mcfunction:
  pigs5k, pigs10k, pigs30k  N pigs spread evenly over an open grass field (~19 per chunk), a glass wall around it
  pigpens                   5,000 pigs in 5 glass pens of 1,000 (16x16 inside: ~4 per block - pushes; cramming off)
  farm                      2,000 cows/sheep/chickens in 4 pens + 300 villagers with 300 job sites + 500 zombies
                            chasing 10 iron golems in a roofed pen (pathfinding; both invulnerable so counts stay)

Every scene forceloads its whole area; a forceload command takes at most 256 chunks, so the big fields have several
`forceload add` lines - the FIRST is what tools/server.py scene_area reads, tools/mobmass.py issues all of them and waits
for the chunks before running the function (a summon into a chunk not loaded yet fails).
Mobs summoned with NBT skip finalizeSpawn (no random babies / equipment / jockeys): the same mobs every run.
"""
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'src', 'scenes', 'resources', 'data', 'sleep', 'function')

RULES = ['gamerule doDaylightCycle false', 'gamerule doWeatherCycle false', 'gamerule doMobSpawning false',
         'gamerule doTraderSpawning false', 'gamerule doPatrolSpawning false', 'gamerule doInsomnia false',
         'gamerule doMobLoot false', 'gamerule randomTickSpeed 3', 'time set noon', 'weather clear']
Y = -60   # the superflat's surface (grass at -61)


def forceloads(x1, z1, x2, z2):
    """forceload lines of at most 16x16 chunks covering blocks x1..x2, z1..z2"""
    out = []
    cx1, cz1, cx2, cz2 = x1 >> 4, z1 >> 4, x2 >> 4, z2 >> 4
    for cx in range(cx1, cx2 + 1, 16):
        for cz in range(cz1, cz2 + 1, 16):
            ex, ez = min(cx + 15, cx2), min(cz + 15, cz2)
            out.append('forceload add %d %d %d %d' % (cx * 16, cz * 16, ex * 16 + 15, ez * 16 + 15))
    return out


def walls(x1, z1, x2, z2, block, h):
    """four wall strips (a hollow fill of a big field is over the 32,768-block fill limit)"""
    y2 = Y + h - 1
    return ['fill %d %d %d %d %d %d %s' % (x1, Y, z1, x2, y2, z1, block),
            'fill %d %d %d %d %d %d %s' % (x1, Y, z2, x2, y2, z2, block),
            'fill %d %d %d %d %d %d %s' % (x1, Y, z1, x1, y2, z2, block),
            'fill %d %d %d %d %d %d %s' % (x2, Y, z1, x2, y2, z2, block)]


def kill(x1, z1, x2, z2):
    return 'kill @e[type=!minecraft:player,x=%d,y=-61,z=%d,dx=%d,dy=12,dz=%d]' % (x1, z1, x2 - x1, z2 - z1)


def grid(n, x1, z1, x2, z2):
    """n points spread evenly over the rectangle (centres of a near-square grid)"""
    w, d = x2 - x1, z2 - z1
    nx = max(1, round(math.sqrt(n * w / d)))
    nz = math.ceil(n / nx)
    sx, sz = w / nx, d / nz
    pts = []
    for i in range(nz):
        for j in range(nx):
            if len(pts) == n:
                return pts
            pts.append((x1 + (j + 0.5) * sx, z1 + (i + 0.5) * sz))
    return pts


def write(name, head, lines):
    path = os.path.join(OUT, 'lag_%s.mcfunction' % name)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(head + lines + ['tellraw @a {"text":"sleep: lag_%s built","color":"yellow"}' % name]) + '\n')
    print('%s: %d lines' % (path, len(head) + len(lines) + 1))


def pigs_spread(name, n, x1, z1, chunks):
    x2, z2 = x1 + chunks * 16 - 1, z1 + chunks * 16 - 1
    head = ['# sleep:lag_%s - %d pigs spread over %dx%d chunks of open grass (~%.0f per chunk), a glass wall around'
            % (name, n, chunks, chunks, n / chunks / chunks),
            '# (the MCRS / Horizon video: 30,000 pigs). Area x %d..%d, z %d..%d; tools/mobmass.py forceloads every line first'
            % (x1, x2, z1, z2)]
    lines = RULES + forceloads(x1, z1, x2, z2) + [kill(x1, z1, x2, z2)] + walls(x1 + 1, z1 + 1, x2 - 1, z2 - 1, 'minecraft:glass', 2)
    lines += ['summon minecraft:pig %.2f %d %.2f {PersistenceRequired:1b}' % (x, Y, z) for x, z in grid(n, x1 + 2, z1 + 2, x2 - 1, z2 - 1)]
    write(name, head, lines)


def pigpens():
    x1, z1 = 5120, 4096
    pens, per = 5, 1000
    x2, z2 = x1 + pens * 24 + 7, z1 + 31
    head = ['# sleep:lag_pigpens - %d pigs in %d glass pens of %d (16x16 inside, ~4 per block): entity pushes, the crowded'
            % (pens * per, pens, per),
            '# case (maxEntityCramming 0: no cramming deaths, the count stays). Area x %d..%d, z %d..%d' % (x1, x2, z1, z2)]
    lines = RULES + forceloads(x1, z1, x2, z2) + [kill(x1, z1, x2, z2), 'gamerule maxEntityCramming 0']
    for p in range(pens):
        px = x1 + 4 + p * 24
        pz = z1 + 4
        lines += walls(px, pz, px + 17, pz + 17, 'minecraft:glass', 3)
        lines += ['summon minecraft:pig %.2f %d %.2f {PersistenceRequired:1b}' % (x, Y, z)
                  for x, z in grid(per, px + 1.2, pz + 1.2, px + 16.8, pz + 16.8)]
    write('pigpens', head, lines)


SITES = ['composter', 'lectern', 'barrel', 'blast_furnace', 'smoker', 'cartography_table', 'fletching_table',
         'smithing_table', 'grindstone', 'stonecutter', 'loom', 'brewing_stand', 'cauldron']


def farm():
    x1, z1 = 6144, 4096
    x2, z2 = x1 + 175, z1 + 143
    head = ['# sleep:lag_farm - a mixed farm: 2,000 cows/sheep/chickens in 4 glass pens, 300 villagers with 300 job sites in',
            '# a fenced 76x76 village, 500 zombies chasing 10 iron golems in a roofed dark stone pen (pathfinding; zombies and',
            '# golems invulnerable so the counts stay). Area x %d..%d, z %d..%d' % (x1, x2, z1, z2)]
    lines = RULES + forceloads(x1, z1, x2, z2) + [kill(x1, z1, x2, z2)]
    # animals: 4 pens 34x34 (inside 32x32), 500 each (cow, sheep, chicken in turn: 700/700/600 over the 4 pens)
    kinds = ['cow'] * 700 + ['sheep'] * 700 + ['chicken'] * 600
    k = 0
    for p in range(4):
        px, pz = x1 + 2 + p * 40, z1 + 2
        lines += walls(px, pz, px + 33, pz + 33, 'minecraft:glass', 3)
        for x, z in grid(500, px + 1, pz + 1, px + 33, pz + 33):
            lines.append('summon minecraft:%s %.2f %d %.2f {PersistenceRequired:1b}' % (kinds[k], x, Y, z))
            k += 1
    # the village: oak fence 77x77, 300 job sites on a grid (4 apart), 300 unemployed villagers between them
    vx, vz = x1 + 2, z1 + 50
    lines += walls(vx, vz, vx + 76, vz + 76, 'minecraft:oak_fence', 1)
    n = 0
    for i in range(18):
        for j in range(18):
            if n == 300:
                break
            lines.append('setblock %d %d %d minecraft:%s' % (vx + 4 + j * 4, Y, vz + 4 + i * 4, SITES[n % len(SITES)]))
            n += 1
    lines += ['summon minecraft:villager %.2f %d %.2f {PersistenceRequired:1b}' % (x, Y, z)
              for x, z in grid(300, vx + 2, vz + 2, vx + 74, vz + 74)]
    # the arena: roofed stone box 50x50 (dark inside: no burning), 10 golems in the middle, 500 zombies around the edge
    ax, az = x1 + 110, z1 + 70
    lines.append('fill %d %d %d %d %d %d minecraft:stone hollow' % (ax, Y - 1, az, ax + 49, Y + 4, az + 49))
    lines.append('fill %d %d %d %d %d %d minecraft:grass_block' % (ax + 1, Y - 1, az + 1, ax + 48, Y - 1, az + 48))
    for x, z in grid(10, ax + 18, az + 18, ax + 32, az + 32):
        lines.append('summon minecraft:iron_golem %.2f %d %.2f {PersistenceRequired:1b,Invulnerable:1b}' % (x, Y, z))
    ring = [(x, z) for x, z in grid(2304, ax + 1, az + 1, ax + 49, az + 49)
            if not (ax + 12 < x < ax + 38 and az + 12 < z < az + 38)]
    step = len(ring) / 500.0
    for i in range(500):
        x, z = ring[int(i * step)]
        lines.append('summon minecraft:zombie %.2f %d %.2f {PersistenceRequired:1b,Invulnerable:1b,CanBreakDoors:0b}' % (x, Y, z))
    write('farm', head, lines)



def raiders():
    """400 witches, 200 pillagers and 100 vindicators around 10 iron golems in a roofed dark pen (a witch / raid farm's
    raiders; everyone invulnerable so the counts stay; no raid)"""
    x1, z1 = 8192, 4096
    x2, z2 = x1 + 63, z1 + 63
    head = ['# sleep:lag_raiders - 400 witches, 200 pillagers, 100 vindicators around 10 iron golems in a roofed stone pen',
            '# 60x60 (all invulnerable). Area x %d..%d, z %d..%d' % (x1, x2, z1, z2)]
    lines = RULES + forceloads(x1, z1, x2, z2) + [kill(x1, z1, x2, z2)]
    ax, az = x1 + 2, z1 + 2
    lines.append('fill %d %d %d %d %d %d minecraft:stone hollow' % (ax, Y - 1, az, ax + 59, Y + 5, az + 59))
    lines.append('fill %d %d %d %d %d %d minecraft:grass_block' % (ax + 1, Y - 1, az + 1, ax + 58, Y - 1, az + 58))
    for x, z in grid(10, ax + 25, az + 25, ax + 35, az + 35):
        lines.append('summon minecraft:iron_golem %.2f %d %.2f {PersistenceRequired:1b,Invulnerable:1b}' % (x, Y, z))
    kinds = ['witch'] * 400 + ['pillager'] * 200 + ['vindicator'] * 100
    ring = [(x, z) for x, z in grid(3364, ax + 1, az + 1, ax + 59, az + 59)
            if not (ax + 20 < x < ax + 40 and az + 20 < z < az + 40)]
    step = len(ring) / float(len(kinds))
    for i, k in enumerate(kinds):
        x, z = ring[int(i * step)]
        lines.append('summon minecraft:%s %.2f %d %.2f {PersistenceRequired:1b,Invulnerable:1b}' % (k, x, Y, z))
    write('raiders', head, lines)

if __name__ == '__main__':
    pigs_spread('pigs5k', 5000, 4096, 4096, 16)
    pigs_spread('pigs10k', 10000, 4096, 4096, 23)
    pigs_spread('pigs30k', 30000, 4096, 4096, 40)
    pigpens()
    farm()
    raiders()
