# sleep:client_darkroom - a closed stone room high in the sky (256 x 256, 94 high) lit only through a 9 x 9 glass window in
# its roof, glowing lines on its floor (block light: skipDark looks at sky light only). The camera stands on the glass
# (under the sky) and looks down; chunks 2-4 and 11-13 of the room have no sky light in them or next to them, and are
# seen through the window. The GPU terrain's skipDark must not leave them out.
# View with -Dsleep.clientAuto.tp=128,246,128,0,90
gamerule doDaylightCycle false
gamerule doWeatherCycle false
time set noon
weather clear
fill 0 150 0 127 150 255 minecraft:stone
fill 128 150 0 255 150 255 minecraft:stone
fill 0 245 0 127 245 255 minecraft:stone
fill 128 245 0 255 245 255 minecraft:stone
fill 0 151 0 255 244 0 minecraft:stone
fill 0 151 255 255 244 255 minecraft:stone
fill 0 151 0 0 244 255 minecraft:stone
fill 255 151 0 255 244 255 minecraft:stone
fill 124 245 124 132 245 132 minecraft:glass
fill 8 150 40 248 150 40 minecraft:sea_lantern
fill 8 150 88 248 150 88 minecraft:sea_lantern
fill 8 150 168 248 150 168 minecraft:sea_lantern
fill 8 150 216 248 150 216 minecraft:sea_lantern
fill 40 150 8 40 150 248 minecraft:glowstone
fill 88 150 8 88 150 248 minecraft:glowstone
fill 168 150 8 168 150 248 minecraft:glowstone
fill 216 150 8 216 150 248 minecraft:glowstone
fill 46 151 46 50 200 50 minecraft:oak_planks
fill 206 151 46 210 200 50 minecraft:oak_planks
fill 46 151 206 50 200 210 minecraft:oak_planks
fill 206 151 206 210 200 210 minecraft:oak_planks
