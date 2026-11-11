# sleep:client_ctm - blocks whose look comes from the world (connected textures through model data): walls of Create's
# casings and framed glass next to plain blocks, nothing moving (needs Create). For the GPU terrain path's mods check:
# those sections must look as vanilla draws them. View with -Dsleep.clientAuto.tp=24,256,4,0,20
gamerule doDaylightCycle false
gamerule doWeatherCycle false
time set noon
weather clear
fill 0 249 16 48 249 64 minecraft:smooth_stone
fill 2 250 20 10 256 20 create:andesite_casing
fill 12 250 20 20 256 20 create:brass_casing
fill 22 250 20 30 256 20 create:copper_casing
fill 32 250 20 40 256 20 create:railway_casing
fill 2 250 30 20 256 30 create:framed_glass
fill 22 250 30 40 256 30 minecraft:stone_bricks
fill 2 250 40 10 252 48 create:andesite_casing
fill 12 250 40 20 252 48 minecraft:oak_planks
