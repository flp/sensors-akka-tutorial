# Author: Franklin Pearsall

import json
import random
import requests
import sys
import time

URL = 'http://localhost:8080/sensorapi/data'

# Usage:
#   - argv[1]: String, location of sensor
#   - argv[2]: Int, weight of car vehicle type
#   - argv[3]: Int, weight of motorcycle vehicle type
#   - argv[4]: Int, weight of bus vehicle type
#   - argv[5]: Int, number of vehicles "detected" per period
#   - argv[6]: Int, period of sending sensor data, in seconds
#
# example data (JSON): {"location": "west-seattle-bridge", "data": ["car", "motorcycle", "car", "car", "bus"]}
def run():
  location = sys.argv[1]
  cw = int(sys.argv[2])
  mw = int(sys.argv[3])
  bw = int(sys.argv[4])
  num_vehicles = int(sys.argv[5])
  period = int(sys.argv[6])
  print 'Sensor started at %s with weights %d:%d:%d (c:m:b). %d vehicles every %d seconds.' % \
    (location, cw, mw, bw, num_vehicles, period)
  print 'Sending data to %s' % URL

  vehicle_distribution = ['car']*cw + ['motorcycle']*mw + ['bus']*bw

  while True:
    vehicles = []
    for n in range(0, num_vehicles):
      vehicles.append(random.choice(vehicle_distribution))

    headers = {'Content-Type': 'application/json'}
    data = {
      'location': location,
      'data': vehicles
    }
    r = requests.post(URL, headers=headers, data=json.dumps(data))

    time.sleep(period)

if __name__ == '__main__':
  run()
