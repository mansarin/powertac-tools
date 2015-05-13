/*
 * Copyright (c) 2015 by John E. Collins
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.powertac.logtool.example;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.HashMap;

import org.apache.log4j.Logger;
import org.powertac.common.BalancingTransaction;
import org.powertac.common.Competition;
import org.powertac.common.MarketTransaction;
import org.powertac.common.TariffTransaction;
import org.powertac.common.msg.BalanceReport;
import org.powertac.common.msg.TimeslotUpdate;
import org.powertac.common.spring.SpringApplicationContext;
import org.powertac.logtool.LogtoolContext;
import org.powertac.logtool.common.DomainObjectReader;
import org.powertac.logtool.common.NewObjectListener;
import org.powertac.logtool.ifc.Analyzer;

/**
 * Example analysis class.
 * gathers info on energy sources, uses, and costs
 * For each timeslot, report includes
 *   purchased energy and cost
 *   customer energy consumption and value
 *   customer energy production and cost
 *   total customer-provided balancing energy and cost
 *   total imbalance and cost
 * 
 * To gather this data, we run through a series of states in each timeslot:
 * 1. Wait for TimeslotUpdate
 * 2. Gather MarketTransaction instances until the first TariffTransaction
 *    shows up. Each MarketTransaction gives price and quantity for a
 *    commitment in the wholesale market.
 * 3. Gather TariffTransaction instances until a BalanceReport shows up.
 *    Each TariffTransaction of type PRODUCE or CONSUME gives local production
 *    and consumption numbers, and prices.
 * 4. Gather TariffTransaction instances produced by the balancing process
 *    until the first BalancingTransaction shows up. The TariffTransaction
 *    instances may be either PRODUCE for up-regulation or CONSUME for
 *    down-regulation. These numbers also modify the production and consumption
 *    data from state #3.
 * 5. Gather BalancingTransaction instances until a TimeslotUpdate arrives.
 *    These give total net imbalance (after the local balancing controls are
 *    applied) for each broker, and the cost to resolve it.
 * 
 * @author John Collins
 */
public class EnergyMixStats
extends LogtoolContext
implements Analyzer
{
  static private Logger log = Logger.getLogger(EnergyMixStats.class.getName());

  private DomainObjectReader dor;

  //private BrokerRepo brokerRepo;

  // Current state
  private enum stateId {TsUpd, MktTx, CustTx, RegTx, BalTx}
  private stateId state;

  // data collectors for current timeslot
  private int timeslot;
  private HashMap<Integer, QtyCost> mktTxSummary;
  private QtyCost used;
  private QtyCost produced;
  private QtyCost upRegulation;
  private QtyCost downRegulation;
  private QtyCost balanceEnergy;

  // summary data collectors
  private QtyCost totalImports;
  private QtyCost totalUsed;
  private QtyCost totalProduced;
  private QtyCost totalUp;
  private QtyCost totalDown;
  private QtyCost totalImbalance;

  // data output file
  private PrintWriter data = null;
  private String dataFilename = "data.txt";
  private boolean dataInit = false;

  /**
   * Constructor does nothing. Call setup() before reading a file to
   * get this to work.
   */
  public EnergyMixStats ()
  {
    super();
    state = stateId.TsUpd;
  }
  
  /**
   * Main method just creates an instance and passes command-line args to
   * its inherited cli() method.
   */
  public static void main (String[] args)
  {
    new EnergyMixStats().cli(args);
  }
  
  /**
   * Takes two args, input filename and output filename
   */
  private void cli (String[] args)
  {
    if (args.length != 2) {
      System.out.println("Usage: <analyzer> input-file output-file");
      return;
    }
    dataFilename = args[1];
    super.cli(args[0], this);
  }

  /**
   * Creates data structures, opens output file. It would be nice to dump
   * the broker names at this point, but they are not known until we hit the
   * first timeslotUpdate while reading the file.
   */
  @Override
  public void setup ()
  {
    dor = (DomainObjectReader) SpringApplicationContext.getBean("reader");
    //brokerRepo = (BrokerRepo) SpringApplicationContext.getBean("brokerRepo");
    mktTxSummary = new HashMap<Integer, QtyCost>();

    dor.registerNewObjectListener(new TimeslotUpdateHandler(),
                                  TimeslotUpdate.class);
    dor.registerNewObjectListener(new MarketTxHandler(),
                                  MarketTransaction.class);
    dor.registerNewObjectListener(new TariffTxHandler(),
                                  TariffTransaction.class);
    dor.registerNewObjectListener(new BalanceRptHandler(),
                                  BalanceReport.class);
    dor.registerNewObjectListener(new BalancingTxHandler(),
                                  BalancingTransaction.class);
    try {
      data = new PrintWriter(new File(dataFilename));
    }
    catch (FileNotFoundException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    dataInit = false;
    state = stateId.TsUpd;
  }

  @Override
  public void report ()
  {
    System.out.println("Game " + Competition.currentCompetition().getName()
                       + ", " + timeslot + " timeslots");
    data.print("Summary, ");
    data.println(String
                 .format("%.3f, %.3f, %.3f, %.3f, %.3f, %.3f, %.3f, %.3f, %.3f, %.3f, %.3f, %.3f",
                         totalImports.quantity, totalImports.cost,
                         totalUsed.quantity, totalUsed.cost,
                         totalProduced.quantity, totalProduced.cost,
                         totalUp.quantity, totalUp.cost,
                         totalDown.quantity, totalDown.cost,
                         totalImbalance.quantity, totalImbalance.cost));
    data.close();
  }

  // Called on timeslotUpdate. Note that there are two of these before
  // the first "real" timeslot. Incoming tariffs are published at the end of
  // the second timeslot (the third call to this method), and so customer
  // consumption against non-default broker tariffs first occurs after
  // four calls.
  private void summarizeTimeslot ()
  {
    if (!dataInit) {
      // first time through nothing to but print header
      data.println("slot, import, cost, cons, revenue, prod, cost, "
                   + "up-reg, cost, down-reg, revenue, imbalance, cost");
      initSummaryData();
      return;
    }
    // skip initial timeslot(s) without data
    if (0.0 == used.quantity) {
      log.warn("skipping ts " + timeslot);
      return;
    }

    // print timeslot index
    data.print(timeslot + ", ");
    // print market data
    QtyCost mktData = mktTxSummary.get(timeslot);
    if (null != mktData) {
      data.print(String.format("%.3f, %.3f, ", mktData.quantity, mktData.cost));
      totalImports.add(mktData);
    }
    else {
      data.print("0.0, 0.0, ");
    }
    // print customer usage, production
    data.print(String.format("%.3f, %.3f, %.3f, %.3f, ",
                             used.quantity, used.cost,
                             produced.quantity, produced.cost));
    totalUsed.add(used);
    totalProduced.add(produced);
    // print regulation usage, production
    data.print(String.format("%.3f, %.3f, %.3f, %.3f, ",
                             upRegulation.quantity, upRegulation.cost,
                             downRegulation.quantity, downRegulation.cost));
    totalUp.add(upRegulation);
    totalDown.add(downRegulation);
    // print balance volume, cost
    data.println(String.format("%.3f, %.3f",
                               balanceEnergy.quantity, balanceEnergy.cost));
    totalImbalance.add(balanceEnergy);
  }

  private void initSummaryData ()
  {
    totalImports = new QtyCost();
    totalUsed = new QtyCost();
    totalProduced = new QtyCost();
    totalUp = new QtyCost();
    totalDown = new QtyCost();
    totalImbalance = new QtyCost();
  }

  private void initTimeslotData ()
  {
    used = new QtyCost();
    produced = new QtyCost();
    upRegulation = new QtyCost();
    downRegulation = new QtyCost();
    balanceEnergy = new QtyCost();
    dataInit = true;
  }

  // -----------------------------------
  // catch TimeslotUpdate events
  class TimeslotUpdateHandler implements NewObjectListener
  {

    @Override
    public void handleNewObject (Object thing)
    {
      TimeslotUpdate msg = (TimeslotUpdate) thing;
      timeslot = msg.getFirstEnabled() - 1;
      log.info("Timeslot " + timeslot);
      summarizeTimeslot();
      initTimeslotData();
      state = stateId.MktTx;
      log.info("Set state to " + state);
    }
  }

  // -----------------------------------
  // catch MarketTransactions
  class MarketTxHandler implements NewObjectListener
  {
    @Override
    public void handleNewObject (Object thing)
    {
      if (stateId.MktTx != state) {
        log.error("incorrect state for mkt tx " + state);
        return;
      }
      MarketTransaction tx = (MarketTransaction) thing;
      if (!tx.getBroker().isWholesale()) {
        int ts = tx.getTimeslot().getSerialNumber();
        QtyCost data = mktTxSummary.get(ts);
        if (null == data) {
          data = new QtyCost();
          mktTxSummary.put(ts, data);
        }
        data.addQty(tx.getMWh());
        data.addCost(tx.getPrice() * Math.abs(tx.getMWh()));
      }
    }
  }

  // -----------------------------------
  // catch TariffTransactions
  class TariffTxHandler implements NewObjectListener
  {
    @Override
    public void handleNewObject (Object thing)
    {
      TariffTransaction tx = (TariffTransaction)thing;
      if (state == stateId.MktTx) {
        // state transition
        state = stateId.CustTx;
        log.info("Set state to " + state);
      }

      if (state == stateId.CustTx) {
        // customer transactions
        if (tx.getTxType() == TariffTransaction.Type.CONSUME) {
          used.addQty(tx.getKWh() / 1000.0);
          used.addCost(tx.getCharge());
        }
        else if (tx.getTxType() == TariffTransaction.Type.PRODUCE) {
          produced.addQty(tx.getKWh() / 1000.0);
          produced.addCost(tx.getCharge());
        }
      }
      else if (state == stateId.RegTx) {
        // regulating transactions
        if (tx.getTxType() == TariffTransaction.Type.PRODUCE) {
          upRegulation.addQty(tx.getKWh() / 1000.0);
          upRegulation.addCost(tx.getCharge());
        }
        else if (tx.getTxType() == TariffTransaction.Type.CONSUME) {
          downRegulation.addQty(tx.getKWh() / 1000.0);
          downRegulation.addCost(tx.getCharge());
        }
      }
      else if (tx.getTxType() == TariffTransaction.Type.CONSUME
          || tx.getTxType() == TariffTransaction.Type.PRODUCE) {
        // should not happen
        log.error("Bad state for tariff tx " + tx.getId() + ": " + state);
      }
    } 
  }

  // -----------------------------------
  // catch the BalancingReport
  class BalanceRptHandler implements NewObjectListener
  {
    @Override
    public void handleNewObject (Object thing)
    {
      if (state != stateId.CustTx) {
        log.error("Bad state for balance report: " + state);
      }
      else {
        state = stateId.RegTx;
        log.info("Set state to " + state);
      }
    }
  }

  // -------------------------------
  // catch BalancingTransactions
  class BalancingTxHandler implements NewObjectListener
  {
    @Override
    public void handleNewObject (Object thing)
    {
      BalancingTransaction tx = (BalancingTransaction) thing;
      if (state == stateId.RegTx) {
        state = stateId.BalTx;
        log.info("Set state to " + state);
      }
      if (state == stateId.BalTx) {
        balanceEnergy.addQty(tx.getKWh() / 1000.0);
        balanceEnergy.addCost(tx.getCharge());
      }
      else {
        log.warn("Bad state for Bal TX " + tx.getId() + ": " + state);
      }
    } 
  }

  class QtyCost
  {
    double quantity = 0.0;
    double cost = 0.0;

    void addQty (double val)
    {
      quantity += val;
    }

    void addCost (double val)
    {
      cost += val;
    }

    void add (QtyCost val)
    {
      quantity += val.quantity;
      cost += val.cost;
    }
  }
}
