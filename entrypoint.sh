#!/bin/sh
echo "Starting server..."
java -jar /server.jar &
echo "Starting daemon..."
java -Xms1024M -Xmx1024M -jar /daemon.jar
