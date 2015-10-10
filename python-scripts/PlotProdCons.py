#!/usr/bin/python
'''
Reads a set of data files representing hourly demand over a set of games.
Lines in each file are formatted as
  timeslot, day-of-week, hour-of-day, production, consumption
Net demand is the negative difference between consumption and production.
Data is gathered and plotted by hour-of-week.
'''

import numpy as np
import scipy as sp
import matplotlib.pyplot as plt
import pylab as pl
from scipy import stats
from pathlib import Path

def dataIter (dataDir):
    '''
    Returns a generator of data files extracted from a specified directory
    '''
    path = Path(dataDir)
    return (name for name in path.glob('*-prod-cons.data'))

def collectData (dataDir):
    '''
    Processes data from data files in the specified directory.
    '''
    for dataFile in dataIter(dataDir):
        # note that dataFile is a Path, not a string
        processFile(str(dataFile))

rawData = [[] for x in range(168)]
def processFile (dataFile):
    '''
    Collects data from a data file into a global 168-column array,
    one column per hour-of-week.
    Note that the day-of-week numbers are in the range [1-7].
    '''
    data = open(dataFile, 'r')
    for line in data.readlines():
        row = line.split(', ')
        dow = int(row[1])
        hod = int(row[2])
        column = (dow - 1) * 24 + hod
        prod = floatMaybe(row[3])
        cons = floatMaybe(row[4])
        if cons < 0.0:
            # omit rows where cons == 0
            net = -prod - cons
            rawData[column].append(net)

def floatMaybe (str):
    '''returns the float representation of a string, unless the string is
     empty, in which case return 0. Should have been a lambda, but not
     with Python.'''
    result = 0.0
    if str != '' :
        result = float(str)
    else:
        print('failed to float', str)
    return result


def plotMeans ():
    '''
    Reduces the raw data into means and 1-sigma error bars, plots result
    '''
    d = [np.array(c) for c in rawData]
    print('Shape:', np.shape(d))
    means = [c.mean() for c in d]
    stds = [c.std() for c in d]
    x = range(np.shape(d)[0])
    plt.figure()
    plt.errorbar(x, means, yerr=stds)
    plt.show()