# sleep:client_ie - every Immersive Engineering multiblock formed (needs the Immersive Engineering mod; /sleep ieform),
# four sets, each a grid 6 wide, 14 blocks apart, on a floor at y 249; ClientAuto -Dsleep.clientAuto.scene=client_ie,
# view with -Dsleep.clientAuto.tp=84,320,0,0,55
gamerule doDaylightCycle false
gamerule doWeatherCycle false
time set noon
weather clear
fill -4 249 12 172 249 170 minecraft:smooth_stone
sleep ieform 0 250 16
sleep ieform 88 250 16
sleep ieform 0 250 96
sleep ieform 88 250 96
