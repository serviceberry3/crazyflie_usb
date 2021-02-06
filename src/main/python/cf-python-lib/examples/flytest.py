# -*- coding: utf-8 -*-
#
#     ||          ____  _ __
#  +------+      / __ )(_) /_______________ _____  ___
#  | 0xBC |     / __  / / __/ ___/ ___/ __ `/_  / / _ \
#  +------+    / /_/ / / /_/ /__/ /  / /_/ / / /_/  __/
#   ||  ||    /_____/_/\__/\___/_/   \__,_/ /___/\___/
#
#  Copyright (C) 2014 Bitcraze AB
#
#  Crazyflie Nano Quadcopter Client
#
#  This program is free software; you can redistribute it and/or
#  modify it under the terms of the GNU General Public License
#  as published by the Free Software Foundation; either version 2
#  of the License, or (at your option) any later version.
#
#  This program is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#  GNU General Public License for more details.
#  You should have received a copy of the GNU General Public License
#  along with this program; if not, write to the Free Software
#  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
#  MA  02110-1301, USA.
"""
Simple example that connects to the first Crazyflie found, ramps up/down
the motors and disconnects.
"""
import logging
import time
from threading import Thread
from threading import Timer
import random

import cflib
import cflib.crtp  # noqa
from cflib.crazyflie import Crazyflie
from cflib.crazyflie.log import LogConfig
from cflib.crazyflie import Console


logging.basicConfig(level=logging.ERROR)

class MotorRampExample:
    """Example that connects to a Crazyflie and ramps the motors up/down and
    the disconnects"""

    def __init__(self, link_uri):
        """ Initialize and run the example with the specified link_uri """

        self._cf = Crazyflie(rw_cache='./cache')

        self._cf.connected.add_callback(self._connected)
        self._cf.disconnected.add_callback(self._disconnected)
        self._cf.connection_failed.add_callback(self._connection_failed)
        self._cf.connection_lost.add_callback(self._connection_lost)

        self._cf.open_link(link_uri)


        print('Connecting to %s' % link_uri)
        self.is_connected = True

        # change the parameters0, 
        self._param_check_list = []
        self._param_groups = []

    # def _connected(self, link_uri):
    #     """ This callback is called form the Crazyflie API when a Crazyflie
    #     has been connected and the TOCs have been downloaded."""

    #     # Start a separate thread to do the motor test.
    #     # Do not hijack the calling thread!
    #     Thread(target=self._ramp_motors).start()

    def _connected(self, link_uri):
        """ This callback is called from the Crazyflie API when a Crazyflie
        has been connected and the TOCs have been downloaded."""
        print('Connected to %s' % link_uri)
        ###########################################################

        # LOG
        # The definition of the logconfig can be made before connecting
        # self._lg_stab = LogConfig(name='packet', period_in_ms=10)
        # self._lg_stab.add_variable('crtp.COMMANDER_FLAG', 'uint8_t')
        # self._lg_stab.add_variable('stabilizer.pitch', 'float')
        # self._lg_stab.add_variable('stabilizer.yaw', 'float')

        # Adding the configuration cannot be done until a Crazyflie is
        # connected, since we need to check that the variables we
        # would like to log are in the TOC.
        # try:
        #     self._cf.log.add_config(self._lg_stab)
        #     # This callback will receive the data
        #     self._lg_stab.data_received_cb.add_callback(self._stab_log_data)
        #     # This callback will be called on errors
        #     self._lg_stab.error_cb.add_callback(self._stab_log_error)
        #     # Start the logging
        #     self._lg_stab.start()
        # except KeyError as e:
        #     print('Could not start log configuration,'
        #           '{} not found in TOC'.format(str(e)))
        # except AttributeError:
        #     print('Could not add Stabilizer log config, bad configuration.')

        ###########################################################

        # PARAM
        # self._cf.param.add_update_callback(group='motorPowerSet', name='enable',
        #                                    cb=self._a_propTest_callback)
        # self._cf.param.set_value('motorPowerSet.enable',
        #                              '{:d}'.format(1))

        # thv = 5000
        # self._cf.param.set_value('motorPowerSet.m4',
        #                              '{:d}'.format(thv))
        # time.sleep(1)
        # self._cf.param.set_value('motorPowerSet.m4',
        #                              '{:d}'.format(0))
        # self._cf.param.set_value('motorPowerSet.m2',
        #                              '{:d}'.format(thv))
        # self._cf.param.set_value('motorPowerSet.m3',
        #                              '{:d}'.format(thv))
        # self._cf.param.set_value('motorPowerSet.m4',
        #                              '{:d}'.format(thv))

        # time.sleep(3)
        # self._cf.param.set_value('motorPowerSet.enable',
                                     # '{:d}'.format(0))
        ###########################################################

        #console = Console(self._cf)

        # RAMP
        # Thread(target=self._ramp_motors).start()
        self._ramp_motors()

        # Start a timer to disconnect in 5s
        t = Timer(5, self._cf.close_link)
        t.start()

    def _a_propTest_callback(self, name, value):
        """Callback for pid_attitude.pitch_kd"""
        print('Readback: {0}={1}'.format(name, value))

    def _stab_log_error(self, logconf, msg):
        """Callback from the log API when an error occurs"""
        print('Error when logging %s: %s' % (logconf.name, msg))

    def _stab_log_data(self, timestamp, data, logconf):
        """Callback froma the log API when data arrives"""
        print('[%d][%s]: %s' % (timestamp, logconf.name, data), flush=True)
    def _connection_failed(self, link_uri, msg):
        """Callback when connection initial connection fails (i.e no Crazyflie
        at the specified address)"""
        print('Connection to %s failed: %s' % (link_uri, msg))

    def _connection_lost(self, link_uri, msg):
        """Callback when disconnected after a connection has been made (i.e
        Crazyflie moves out of range)"""
        print('Connection to %s lost: %s' % (link_uri, msg))

    def _disconnected(self, link_uri):
        """Callback when the Crazyflie is disconnected (called in all cases)"""
        print('Disconnected from %s' % link_uri)

    def _ramp_motors(self):
        print("_ramp_motors")

        thrust_mult = 1
        thrust_step = 100
        thrust_dstep = 10
        thrust = 3000
        pitch = 0
        roll = 0
        yawrate = 0
        start_height = 0.05
        target_height = 0.2 #the distance is not accurate, 1.2 => 1.5m

        # Unlock startup thrust protection
        # print('begin', flush=True)
        self._cf.commander.send_setpoint(0, 0, 0, 0)

        
        #thrust test
        self._cf.commander.send_setpoint(0, 0, 0, 0)
        self._cf.commander.send_setpoint(0, 0, 0, 10001)
        time.sleep(5)

        '''
        print("Lifting")
        cnt = 0

        while cnt < 50:
            # self._cf.commander.send_height(0, 0, 0, target_height)
            self._cf.commander.send_height(0, 0, 0, start_height + (target_height - start_height) * (cnt / 50.0))
            # self._cf.commander.send_setpoint(0, 0, 0, thrust)
            # self._cf.commander.send_setpoint(0, 0, 0, 18000)
            cnt = cnt + 1
            time.sleep(0.02)

        print("hover")
        cnt = 0
        while cnt < 30:
            self._cf.commander.send_height(0, 0, 0, target_height) # (forward, left)
            cnt = cnt + 1
            time.sleep(0.1)

        print("down")
        cnt = 0
        while cnt < 50:
            self._cf.commander.send_height(0, 0, 0, (-target_height + start_height)*(cnt / 50.0) + target_height)
            cnt += 1
            time.sleep(0.05)'''



        '''
        #Just test propellers
        print("Propeller test")
        cnt = 0
        while cnt < 30:
            self._cf.commander.send_height(0, 0, 0, 0.1) # (forward, left)
            #self._cf.commander.send_motorValue(0, 40000)
            #self._cf.commander.send_motorValue(1, 40000)
            #self._cf.commander.send_motorValue(2, 40000)
            #self._cf.commander.send_motorValue(3, 40000)
            cnt = cnt + 1
            time.sleep(0.1)'''


        # while thrust >= 1000 and thrust < 10000:
        #     self._cf.commander.send_setpoint(0, 0, 0, thrust)
        #     time.sleep(0.1)
        #     thrust += thrust_step * thrust_mult

        # cnt = 0
        # while cnt < 1:
        #     self._cf.commander.send_setpoint(roll, pitch, yawrate, thrust)
        #     cnt = cnt + 1
        #     time.sleep(0.1)
        # print("cnt")
        # while thrust >= 37000:
        #     self._cf.commander.send_setpoint(roll, pitch, yawrate, thrust)
        #     time.sleep(0.1)
        #     thrust -= thrust_dstep * thrust_mult
        self._cf.commander.send_setpoint(0, 0, 0, 0)
        print('end', flush=True)

        # Make sure that the last packet leaves before the link is closed
        # since the message queue is not flushed before closing
        time.sleep(3)
        # self._cf.close_link()


if __name__ == '__main__':
    # Initialize the low-level drivers (don't list the debug drivers)
    cflib.crtp.init_drivers(enable_debug_driver=False)

    # Scan for Crazyflies and use the first one found
    print('Scanning interfaces for Crazyflies...')
    available = cflib.crtp.scan_interfaces()
    print('Crazyflies found:')

    for i in available:
        print(i[0])

    #if there's a cf available, run the flytest
    if len(available) > 0:
        le = MotorRampExample(available[0][0])

    #otherwise print error and exit
    else:
        print('No Crazyflies found, cannot run example')
    # The Crazyflie lib doesn't contain anything to keep the application alive,
    # so this is where your application should do something. In our case we
    # are just waiting until we are disconnected.
    # while le.is_connected:
    #     time.sleep(1)
