# sleep:client_ie_crushers - 60 Immersive Engineering crushers, formed (needs Immersive Engineering; /sleep ieform with a
# name filter), a grid 6 wide, 7 blocks apart; working with -Dsleep.test.stimulate=-4,12,50,90 -Dsleep.test.stimulate.work=true
# (energy and items from every side); view with -Dsleep.clientAuto.tp=18,268,0,0,45
gamerule doDaylightCycle false
gamerule doWeatherCycle false
time set noon
weather clear
fill -4 249 12 50 249 90 minecraft:smooth_stone
sleep ieform 0 250 16 crusher 60 7
