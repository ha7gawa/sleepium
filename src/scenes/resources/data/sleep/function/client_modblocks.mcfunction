# sleep:client_modblocks - walls of several big mods' blocks (AE2, Create, Mekanism, Immersive Engineering, Sophisticated
# Storage) next to each other, connected glass and casings (model data), block entities with renderers, nothing moving.
# For the GPU terrain path with many mods at once: the picture as vanilla draws it. Needs all five mods.
# View with -Dsleep.clientAuto.tp=24,256,4,0,20
gamerule doDaylightCycle false
gamerule doWeatherCycle false
time set noon
weather clear
fill 0 249 16 50 249 64 minecraft:smooth_stone
fill 2 250 20 8 254 20 ae2:quartz_glass
fill 10 250 20 16 254 20 ae2:quartz_vibrant_glass
fill 18 250 20 24 254 20 ae2:fluix_block
fill 26 250 20 32 254 20 ae2:sky_stone_block
fill 34 250 20 40 254 20 create:brass_casing
fill 42 250 20 48 254 20 create:andesite_casing
fill 2 250 30 8 254 30 mekanism:steel_casing
fill 10 250 30 16 254 30 mekanism:structural_glass
fill 18 250 30 24 254 30 mekanism:block_osmium
fill 26 250 30 32 254 30 immersiveengineering:treated_wood_horizontal
fill 34 250 30 40 254 30 immersiveengineering:steel_scaffolding_standard
fill 42 250 30 48 254 30 immersiveengineering:sheetmetal_steel
setblock 4 250 40 ae2:controller
setblock 8 250 40 mekanism:basic_energy_cube
setblock 12 250 40 sophisticatedstorage:barrel
setblock 16 250 40 sophisticatedstorage:chest
setblock 20 250 40 sophisticatedstorage:limited_barrel_4
fill 24 250 40 40 250 40 immersiveengineering:conveyor_basic
