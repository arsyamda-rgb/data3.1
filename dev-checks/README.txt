Standalone math check for RaceChanceCalculator (no Maven, no Paper needed). From the project root:

  mkdir -p /tmp/out
  javac -d /tmp/out src/main/java/com/veyrion/character/race/RaceChanceCalculator.java dev-checks/RaceChanceCheck.java
  java -cp /tmp/out com.veyrion.character.race.RaceChanceCheck

Expected last line: ALL CHECKS PASSED. This folder is outside src/, so Maven ignores it.
