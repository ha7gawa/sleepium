# sleep:client_mods - every block of Immersive Engineering, Mekanism and Create placed on a floor at y 249 (each mod a square
# grid, 3 blocks apart; mods that are not installed place nothing): which renderers draw what (-Dlab.quadgpu.stats).
# View with -Dsleep.clientAuto.tp=60,268,-10,0,40
gamerule doDaylightCycle false
gamerule doWeatherCycle false
time set noon
weather clear
fill -2 249 -2 130 249 60 minecraft:smooth_stone
sleep placeall immersiveengineering 0 250 0 3
sleep placeall mekanism 45 250 0 3
sleep placeall mekanismgenerators 90 250 0 3
sleep placeall create 0 250 62 3
fill -2 249 60 100 249 150 minecraft:smooth_stone
