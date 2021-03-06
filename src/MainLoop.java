
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalInput;
import com.pi4j.io.gpio.PinPullResistance;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;

import Helper.Config;

public class MainLoop extends Thread implements GpioPinListenerDigital  {


	// define the log level for this Class
	private static final Level LOGLEVEL = Level.INFO;
	private static Logger logger =  Logger.getLogger( MainLoop.class.getName() );

	//timeEvents contains all timeevents, loaded from the config file
	//The arraylist contains all timeevents  at the same time 
	private  HashMap<Integer, ArrayList<TimeEvent> > timeEvents = new HashMap<Integer, ArrayList<TimeEvent>>(); 

	// channelValues contain channel values  with the channel number as Integer 
	private  ConcurrentHashMap<Integer, ChannelValue> channelValues = new ConcurrentHashMap<Integer, ChannelValue>(); 


	private  Config conf;

	private ArtnetDevice artnetDevice = new ArtnetDevice(); 


	//map the Time events value to this values.
	private int startValue, endValue;

	// how long the thread sleeps after each run
	private int sleepTime; 

	// the min and max changes if the sensor in on 
	private int minPercentageChange, maxPercentageChange;
	
	// define how long the value of a channel is hold, after a sensor changed it
	//in milliseconds
	private int sensorHoldTime;
	
	private static Random random;


	// create gpio controller instance
	final GpioController gpio = GpioFactory.getInstance();

	// provision gpio pin #02 as an input pin with its internal pull down resistor enabled
	// (configure pin edge to both rising and falling to get notified for HIGH and LOW state
	// changes)	
	GpioPinDigitalInput sensor1 = gpio.provisionDigitalInputPin(RaspiPin.GPIO_07,             // PIN NUMBER
			"Sensor1",                   // PIN FRIENDLY NAME (optional)
			PinPullResistance.PULL_DOWN); // PIN RESISTANCE (optional)

	GpioPinDigitalInput sensor2 = gpio.provisionDigitalInputPin(RaspiPin.GPIO_00,             // PIN NUMBER
			"Sensor2",                   // PIN FRIENDLY NAME (optional)
			PinPullResistance.PULL_DOWN); // PIN RESISTANCE (optional)

	
	public MainLoop(Config conf) {
		super();
		this.conf = conf;
		logger.setLevel(LOGLEVEL);
		
		// add this for events on the inputpins
		sensor1.addListener(this);
		sensor2.addListener(this);
		sleepTime = 1000;
		sensorHoldTime = 10;
		random = new Random();
	}


	@Override
	public void run() {
		// TODO Auto-generated method stub
		super.run();


		loadPropertieFile();
		artnetDevice.start();


		while(true){

			// check if a new time event occurred 
			checkTimeEvents();
			
			checkValuesInSensorMode();
			sendData();

			// wait some time to check next
			try {
				MainLoop.sleep(sleepTime);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}



	}

	/**
	 * returns a random channel from 0 to maxChannel
	 * @param maxChannel
	 * @return
	 */
	private int getRandomChannel(int maxChannel){

		return random.nextInt(maxChannel);
		

	}

	private void checkValuesInSensorMode() {

		synchronized (channelValues) {
			
		
			for ( ChannelValue value : channelValues.values() ) {
			
					if ( value.isSensorMode() && value.getValueDuration() > sensorHoldTime) {
						int oldValue = value.getOldValue();
						int currentValue = value.getCurrentValue();
						value.setCurrentValue(oldValue);
						value.setOldValue(currentValue);
						value.setSensorMode(false);
					}
			}
		}
	}


	private int getRandomValue(int currentValue) {

		int newValue;

		int percentage = minPercentageChange + (int)(Math.random() * ((maxPercentageChange - minPercentageChange) + 1));		

		if (Math.random() < 0.5) {
			newValue =  currentValue - currentValue * percentage/100;
			if (newValue < 0 ) {
				newValue = 0;
			}
		}else{
			newValue =  currentValue + currentValue * percentage/100;
			if (newValue > 255 ) {
				newValue = 255;
			}
		}
		return newValue;
	}


	private void sendData() {
		artnetDevice.sendValuesForChannels( channelValues);
		//artnetDevice.allChannelsOn();
	}


	private void checkTimeEvents() {

		Date date = new Date(System.currentTimeMillis());
		//logger.info("Current Date = " + date.toString());

		Calendar calendar = Calendar.getInstance();
		calendar.setTime(date);
		int hours = calendar.get(Calendar.HOUR_OF_DAY);
		int minutes = calendar.get(Calendar.MINUTE);

		//time is written in xxxx 
		int currentFormatedTime = hours * 100 + minutes;

		if (timeEvents.containsKey(currentFormatedTime)) {
			// TODO if it matches timeevent it jumps in here for the whole minute

			ArrayList<TimeEvent> eventsOnTime = timeEvents.get(currentFormatedTime);

			// go through all events on that time
			for (TimeEvent event : eventsOnTime) {

				// because within a minute we would find the timeevent several times, check if 
				// the last time we found this time event is more than a minute ago. 
				if( (System.currentTimeMillis() - event.getTimeFound()  ) > 60000 ){
					logger.info("Found time event");
				
				int channel = event.getChannel();
				int value = event.getValue();
				
				
				//the value is defined from 0% to 100% so we have to map it to dmx range 0-255
				value = startValue + (endValue - startValue) * value/100;
				logger.info("Channel: " + channel + " Value: " + value);
				channelValues.get(channel).setCurrentValue(value);
				// set the old value, not used yet, but maybe to change values depending on the previous one
				channelValues.get(channel).setOldValue(value);
				// set the time when we found this event
				event.setTimeFound(System.currentTimeMillis());
				}
			}



		}


	}

	private void loadPropertieFile(){


		loadTimeEvents();
		loadMappingValues();
		loadSleepTime();
		loadSensorProperties();
		loadInitValues();
		logger.info("Properties loaded");


	}

	private void loadInitValues() {

		int initValue  = conf.getInt("initValue");
		channelValues.put(0, new ChannelValue(initValue));
		channelValues.put(1, new ChannelValue(initValue));
		channelValues.put(2, new ChannelValue(initValue));
		channelValues.put(3, new ChannelValue(initValue));	

	}


	private void loadSensorProperties() {
		int min  = conf.getInt("minPercentageChange");
		int max = conf.getInt("maxPercentageChange");	

		if( minPercentageChange < 0 || minPercentageChange > 100 
				|| maxPercentageChange < 0 || maxPercentageChange > 100){
			logger.warning(" Percentage values have to be between 0 and 100");
		}else{

			minPercentageChange = min;
			maxPercentageChange = max;
		}
		int sht  = conf.getInt("sensorHoldTime");

		if( sht < 0 ){
			logger.warning(" Sensor hold time has to be greater 0, reset to default 1000 milliseconds");
			sht = 1000;
		}else{
			sensorHoldTime = sht;

		}


	}


	private void loadSleepTime() {

		sleepTime = conf.getInt("sleepTime");
	}


	private void loadMappingValues() {
		startValue = conf.getInt("startValue");
		endValue = conf.getInt("endValue");

	}


	private  void loadTimeEvents() {

		String[] events = conf.getString("timeEvents").split(",");

		// parse all time events
		for (String event : events) {
			String[] eventData = event.split("-");

			//check if right format
			//skip invalid ones
			if (eventData.length != 3) {
				logger.warning( "Event Data not correct. timecode - channel - value");
				continue;
			}
			if ( eventData[0].trim().length() !=  4) {
				logger.warning( "Event Time not correct. format = xxxx");
				continue;
			}


			int channel = Integer.parseInt( eventData[1].trim());
			int value = Integer.parseInt( eventData[2].trim());
			int time = Integer.parseInt(eventData[0].trim());

			if (time < 0) {
				logger.warning( "Time has to be greater 0");
				continue;
			}
			if (channel < 0 || channel > 512) {
				logger.warning( "Channel has to be between 0 and 512");
				continue;
			}
			if (value < 0 || value > 100) {
				logger.warning( "Value has to be between 0 and 100");
				continue;
			}

			TimeEvent timeEvent = new TimeEvent(time,channel,value);


			// if our hashmap already contains an event on that time, put it in the array list
			//else create a new one 
			if (timeEvents.containsKey(time)) {

				timeEvents.get(time).add(timeEvent);

			}else{
				ArrayList<TimeEvent> e = new ArrayList<TimeEvent>();
				e.add(timeEvent);
				timeEvents.put(time, e );
			}
		}


	}

	// callback from the sensors
	@Override
	public void handleGpioPinDigitalStateChangeEvent( GpioPinDigitalStateChangeEvent event) {

		logger.info("Input Event from " + event.getPin().toString() + ". State = " +  event.getState());

		
		//only trigger high events
		if(event.getState().isHigh()){

		
			int randomChannel;
			// choice a random channel 
			randomChannel = getRandomChannel(4);

			//synchronized (channelValues) {  // don't need to synchronize it, because we use a ConcurrentHashMap
				
				ChannelValue randomChannelValue;
			randomChannelValue = channelValues.get(randomChannel);
			if(randomChannelValue.isSensorMode() != true)
			{
				int currentValue = randomChannelValue.getCurrentValue();
			
				randomChannelValue.setOldValue(currentValue);
			
				randomChannelValue.setSensorMode(true);
				randomChannelValue.setCurrentValue(255);
				randomChannelValue.setTimeValueChangedBySensor(System.currentTimeMillis());

			//}
			logger.info("Value changed for channel " + randomChannel );
		}

		}

	}
	
}