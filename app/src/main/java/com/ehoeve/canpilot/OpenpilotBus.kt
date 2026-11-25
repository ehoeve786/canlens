package com.ehoeve.canpilot

import com.ehoeve.canpilot.core.Dbc

/**
 * openpilot's own reading of the car (its carState), which dashkitd on the comma sends with the chassis bus as bus
 * [BUS], ten frames a second: references for Decode that cost nothing, already decoded and checked by openpilot.
 * Packed as 16-bit little-endian numbers, flags in [OP_states].
 */
object OpenpilotBus {
    const val BUS = 8

    val DBC: Dbc = Dbc.parse("""
        BO_ 1 OP_motion: 8 OP
         SG_ OP_vEgo : 0|16@1- (0.01,0) [0|0] "m/s" Vector__XXX
         SG_ OP_aEgo : 16|16@1- (0.01,0) [0|0] "m/s2" Vector__XXX
         SG_ OP_yawRate : 32|16@1- (0.001,0) [0|0] "rad/s" Vector__XXX
         SG_ OP_steeringAngleDeg : 48|16@1- (0.1,0) [0|0] "deg" Vector__XXX
        BO_ 2 OP_driver: 8 OP
         SG_ OP_steeringTorque : 0|16@1- (1,0) [0|0] "" Vector__XXX
         SG_ OP_steeringRateDeg : 16|16@1- (0.1,0) [0|0] "deg/s" Vector__XXX
         SG_ OP_gas : 32|16@1- (0.001,0) [0|0] "" Vector__XXX
         SG_ OP_brake : 48|16@1- (0.001,0) [0|0] "" Vector__XXX
        BO_ 3 OP_states: 8 OP
         SG_ OP_leftBlinker : 0|1@1+ (1,0) [0|0] "" Vector__XXX
         SG_ OP_rightBlinker : 1|1@1+ (1,0) [0|0] "" Vector__XXX
         SG_ OP_doorOpen : 2|1@1+ (1,0) [0|0] "" Vector__XXX
         SG_ OP_seatbeltUnlatched : 3|1@1+ (1,0) [0|0] "" Vector__XXX
         SG_ OP_brakePressed : 4|1@1+ (1,0) [0|0] "" Vector__XXX
         SG_ OP_gasPressed : 5|1@1+ (1,0) [0|0] "" Vector__XXX
         SG_ OP_standstill : 6|1@1+ (1,0) [0|0] "" Vector__XXX
         SG_ OP_steeringPressed : 7|1@1+ (1,0) [0|0] "" Vector__XXX
         SG_ OP_gearShifter : 8|4@1+ (1,0) [0|0] "" Vector__XXX
         SG_ OP_cruiseEnabled : 12|1@1+ (1,0) [0|0] "" Vector__XXX
         SG_ OP_cruiseSpeed : 16|16@1- (0.01,0) [0|0] "m/s" Vector__XXX
        VAL_ 3 OP_gearShifter 0 "UNKNOWN" 1 "PARK" 2 "DRIVE" 3 "NEUTRAL" 4 "REVERSE" 5 "SPORT" 6 "LOW" 7 "BRAKE" 8 "ECO" 9 "MANUMATIC" ;
    """.trimIndent(), "openpilot")
}
