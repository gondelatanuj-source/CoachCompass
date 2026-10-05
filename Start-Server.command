#!/bin/zsh
# Double-click to start the CoachCompass server, then open the station board.
cd "$(dirname "$0")/server"
(sleep 1 && open http://localhost:8080) &
node server.js
