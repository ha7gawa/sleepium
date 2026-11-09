# sleep:client_ie_crushers500 - 500 Immersive Engineering crushers, formed (needs Immersive Engineering), a square grid 23
# wide, 7 blocks apart (161 x 154 blocks); working with -Dsleep.test.stimulate=-4,12,166,174 -Dsleep.test.stimulate.work=true;
# view with -Dsleep.clientAuto.tp=80,300,-30,0,50
gamerule doDaylightCycle false
gamerule doWeatherCycle false
time set noon
weather clear
fill -4 249 12 166 249 174 minecraft:smooth_stone
sleep ieform 0 250 16 crusher 500 7
