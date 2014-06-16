/* 
 * This file is part of Transitime.org
 * 
 * Transitime.org is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL) as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * Transitime.org is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transitime.org .  If not, see <http://www.gnu.org/licenses/>.
 */
package org.transitime.statistics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.db.structs.ArrivalDeparture;
import org.transitime.db.structs.ArrivalDeparture.ArrivalsOrDepartures;
import org.transitime.gtfs.gtfsStructs.GtfsExtendedStopTime;
import org.transitime.gtfs.gtfsStructs.GtfsFrequency;
import org.transitime.gtfs.gtfsStructs.GtfsStopTime;
import org.transitime.gtfs.readers.GtfsFrequenciesReader;
import org.transitime.gtfs.readers.GtfsStopTimesReader;
import org.transitime.gtfs.writers.GtfsExtendedStopTimesWriter;
import org.transitime.gtfs.writers.GtfsStopTimesWriter;
import org.transitime.utils.MapKey;
import org.transitime.utils.Statistics;
import org.transitime.utils.StringUtils;
import org.transitime.utils.Time;

/**
 * For processing arrival/departure times based on AVL data in order to
 * determine more accurate schedule information. The results are output into new
 * stop_times GTFS files. Data is handled on a per trip basis. This means that
 * for regular schedule based systems that run a trip only once per day you need
 * several days of data to get multiple data points. But for frequency based
 * configurations where a trip is run multiple times a day each run of that trip
 * for the day is processed into a single value.
 * <p>
 * This class also processes schedule adherence information so one can determine
 * what the schedule adherence was with the old stop_times and what it would be
 * with the new ones. This allows one to see directly what kind of improvement
 * using the new stop_times will provide. The schedule adherence information is
 * included in the log file, not in the new stop_times files.
 * <p>
 * The results are output into the original GTFS directory as two new stop times
 * files: stop_times.txt_new and stop_times.txt_extended. Note that the original
 * stop_times.txt file will not be overwritten. If you want to use the new
 * stop_times.txt_new file you need to change its name to stop_times.txt,
 * thereby overwriting the old file.
 * <p>
 * The stop_times.txt_new file has exactly the same format as the standard
 * stop_times.txt file. The stop_times.txt_extended file contains the standard
 * data but also adds some very useful columns including the original stop time
 * so you can see how much it is being changed, the min and max arrival &
 * departure times, the standard deviation so you can see the distribution of
 * times, and the number of data points so you can see how many trips were used
 * to generate the values.
 * <p>
 * The order of the rows in the new stop_times files will not necessarily be the
 * same as the original stop_times file. If the ordering of the original
 * stop_times file is adequate such that trips are grouped together and that the
 * stop_sequence increases within the trip, then the new stop_times files can
 * have the same order. But if there are issues with the ordering of the data in
 * the original stop_times file, which is somewhat common, then the data is
 * first sorted so that can determine the first stops of trips, which is
 * important for the GTFS data is frequency based. This leads to a different
 * ordering for the stop_times.txt_new and stop_times.txt_extended files.
 * <p>
 * To process the data this class reads in arrival and departure data from the
 * database. It batch reads the data 500,000 datapoints at a time, a value
 * chosen to make db reading quick (want a high number) without using too much
 * heap memory at once (want a low number). The arrivals and departures data is
 * read into maps <code>Map<String, Map<TripStopKey, List<Integer>>></code>
 * using <code>readInArrivalsOrDeparturesFromDb()</code>. The map is keyed on
 * routeId so that can handle each route separately (though this isn't truly
 * needed). The data is simply stored as Integers indicating the time of day of
 * the arrival or departure. Once this data is determined the ArrivalDeparture
 * object is not needed anymore and can be garbage collected. When reading in
 * departures it also puts the trip departure times into
 * departureTimesFromTerminalMap so that can determine elapsed time for when
 * frequency based trips are used.
 * <p>
 * Once all of the arrival and departure times have been processed into a map
 * statistics is used to determine which is the best arrival/departure for the
 * stop_times output. The goal is to use a time such that only the a desired
 * fraction of arrivals/departures will be early. For example, if you want only
 * 20% of the vehicle to be early with respect to the schedule time, which is
 * reasonable because for passengers it is better for vehicles to be late rather
 * then early so they don't miss the vehicle, then the value should be 0.2. This
 * desiredFractionEarly value is specified when the ScheduleStatistics object is
 * constructed.
 * <p>
 * The way the software tries to achieve the desiredFractionEarly is by assuming
 * there is a Gaussian distribution of the times. By using the standard
 * deviation of a Gaussian distribution the software estimates the value to use
 * to such that desiredFractionEarly will be attained. Of course the
 * distribution is not truly Gaussian. Therefore several iterations are used to
 * adjust the value in order to get the desired results.
 * <p>
 * The results are then output into the stop_times.txt_new and
 * stop_times.txt_extended files described above.
 * <p>
 * TODO this class should really be split into creating the maps of data and
 * then actually processing the stats.
 * 
 * @author SkiBu Smith
 * 
 */
public class ScheduleStatistics {

	private final String projectId;
	// To specify where to read and write the GTFS data
	private final String gtfsDirectoryName;
	// Tells the db query the time range to use
	private final Date beginTime;
	private final Date endTime;
	// So can convert from between epoch times and seconds into day
	private final Time timeForUsingCalendar;
	// Specifies the desired number of the arrival times that should be early
	private final double desiredFractionEarly;
	// So can not use the more accurate time for the first stop of a trip.
	// This can be important if there will be only one schedule for both
	// drivers and passengers.
	private final boolean doNotUpdateFirstStopOfTrip;

	// Number of iterations to use when determining how many standard deviations
	// are needed to achieve the desired desiredFractionEarly
	private static final int NUMBER_ITERATIONS = 5;

	// Specifies how consistent arrival/departure times from Db must be
	// in order to be used.
	private final int allowableDifferenceFromMeanSecs;
	private final int allowableDifferenceFromOriginalTimeSecs;

	// For schedule adherence
	private final int allowableEarlySecs;
	private final int allowableLateSecs;

	// The GTFS stop_times.txt data. Keyed by trip/stop.
	private final Map<TripStopKey, GtfsStopTime> gtfsStopTimes;

	// The GTFS frequencies.txt info. Keyed on tripId. For keeping track
	// of which trips are configured to be frequency based such that the
	// arrival/departure time has to be converted to seconds since
	// configured beginning of trip.
	private final Set<String> gtfsFrequencyBasedTrips;

	private final Map<TerminalDeparturesKey, Integer> 
		departureTimesFromTerminalMap = 
			new HashMap<TerminalDeparturesKey, Integer>();

	private static final Logger logger = 
			LoggerFactory.getLogger(ScheduleStatistics.class);

	/**
	 * Special MapKey class so that can make sure using the proper key for the
	 * several maps in this class.
	 */
	public static class TripStopKey extends MapKey {
		private TripStopKey(String tripId, String stopId) {
			super(tripId, stopId);
		}

		@Override
		public String toString() {
			return "TripStopKey [" + "tripId=" + o1 + ", stopId=" + o2 + "]";
		}
	}

	/**
	 * Special MapKey class so that can make sure using the proper key for the
	 * departureTimesFromTerminalMap map in this class. Needs vehicleId because
	 * multiple vehicles could be assigned to the same block. Need dayOfYear so
	 * can handle arrival/departure data for multiple days.
	 */
	public static class TerminalDeparturesKey extends MapKey {
		private TerminalDeparturesKey(String vehicleId, String blockId,
				int dayOfYear) {
			super(vehicleId, blockId, dayOfYear);
		}

		@Override
		public String toString() {
			return "TerminalDeparturesKey [" + "vehicleId=" + o1 + ", blockId="
					+ o2 + ", dayOfYear=" + o3 + "]";
		}
	}

	/********************** Member Functions **************************/

	/**
	 * 
	 * @param projectId
	 * @param gtfsDirectoryName
	 * @param beginTime
	 * @param endTime
	 * @param timeForUsingCalendar
	 * @param desiredFractionEarly
	 *            how many arrival/departures should be early
	 * @param allowableDifferenceFromMeanSecs
	 * @param allowableDifferenceFromOriginalTimeSecs
	 * @param doNotUpdateFirstStopOfTrip
	 * @param allowableEarlySecs
	 * @param allowableLateSecs
	 */
	public ScheduleStatistics(String projectId, String gtfsDirectoryName,
			Date beginTime, Date endTime, Time timeForUsingCalendar,
			double desiredFractionEarly, int allowableDifferenceFromMeanSecs,
			int allowableDifferenceFromOriginalTimeSecs,
			boolean doNotUpdateFirstStopOfTrip, int allowableEarlySecs,
			int allowableLateSecs) {
		this.projectId = projectId;
		this.gtfsDirectoryName = gtfsDirectoryName;
		this.beginTime = beginTime;
		this.endTime = endTime;
		this.timeForUsingCalendar = timeForUsingCalendar;
		this.desiredFractionEarly = desiredFractionEarly;
		this.allowableDifferenceFromMeanSecs = allowableDifferenceFromMeanSecs;
		this.allowableDifferenceFromOriginalTimeSecs = 
				allowableDifferenceFromOriginalTimeSecs;
		this.doNotUpdateFirstStopOfTrip = doNotUpdateFirstStopOfTrip;
		this.allowableEarlySecs = allowableEarlySecs;
		this.allowableLateSecs = allowableLateSecs;

		this.gtfsStopTimes = getGtfsStopTimes(gtfsDirectoryName);
		this.gtfsFrequencyBasedTrips = 
				getFrequencyBasedTrips(gtfsDirectoryName);
	}

	/**
	 * Reads in GTFS stop_times.txt file from the GTFS directory specified by
	 * the gtfsDirectoryName command line option. The map is actually an ordered
	 * one so that can both look up gtfsStopTimes by trip & stop so that can do
	 * proper filtered of data from db yet still output the the stop_times.txt
	 * data in the same order as the original file.
	 * 
	 * @param gtfsDirectoryName
	 *            Where to find the GTFS stop_times.txt file
	 * @return Ordered map of the GtfsStopTimes. Keyed on tripId/stopId.
	 */
	private static Map<TripStopKey, GtfsStopTime> getGtfsStopTimes(
			String gtfsDirectoryName) {
		// Read in stop_times.txt file
		logger.info("Reading in original stop_times.txt file...");

		// Get list of stop_times, but these can be a bit out of
		// order with respect to stop sequence within a trip.
		GtfsStopTimesReader stopTimesReader = 
				new GtfsStopTimesReader(gtfsDirectoryName);
		List<GtfsStopTime> gtfsStopTimesList = stopTimesReader.get(100000);

		// Determine if any of the stop times are out of order. If any are
		// then will sort the list. This is necessary because to know which
		// is the first stop of a trip because it is treated differently.
		// Plus really want the stop times in order because it makes it easier
		// to understand the data in the resulting stop_times.txt file. But
		// don't want to sort and change the ordering if the trips are already
		// together and the stop sequences are in order. This way the new
		// stop_times file can be in the same order as the original one,
		// as long as the original doesn't have problems with its order.
		Set<String> tripIdsInvestigated = new HashSet<String>();
		boolean orderProblem = false;
		for (int i = 1; i < gtfsStopTimesList.size(); ++i) {
			GtfsStopTime current = gtfsStopTimesList.get(i);
			GtfsStopTime previous = gtfsStopTimesList.get(i - 1);
			boolean tripAlreadyDealtWith = 
					tripIdsInvestigated.contains(current.getTripId());
			if (tripAlreadyDealtWith) {
				// Already have encountered this trip so make sure the
				// new stop time is in proper order.
				if (!current.getTripId().equals(previous.getTripId())
						|| current.getStopSequence() < previous
								.getStopSequence()) {
					orderProblem = true;
					break;
				}
			} else {
				// Its first stop time of trip so order is OK.
				// Record that have dealt with this trip ID.
				tripIdsInvestigated.add(current.getTripId());
			}
		}

		if (orderProblem) {
			// Sort the list so that the trips are grouped together and so that
			// the stop sequences for the trips are in order. This means that
			// when the stop_times are written out they will be in a different
			// order than originally.
			Collections.sort(gtfsStopTimesList, new Comparator<GtfsStopTime>() {
				@Override
				public int compare(GtfsStopTime arg0, GtfsStopTime arg1) {
					int tripCompare = arg0.getTripId().compareTo(
							arg1.getTripId());
					if (tripCompare != 0)
						return tripCompare;

					// Trip IDs are the same
					if (arg0.getStopSequence() == arg1.getStopSequence()) {
						return 0;
					}
					return arg0.getStopSequence() < arg1.getStopSequence() ? 
							-1 : 1;
				}
			});
		}

		// Create the ordered map to be returned
		Map<TripStopKey, GtfsStopTime> gtfsStopTimesMap = 
				new LinkedHashMap<TripStopKey, GtfsStopTime>(
						gtfsStopTimesList.size());
		for (GtfsStopTime gtfsStopTime : gtfsStopTimesList) {
			TripStopKey key = getTripStopKey(gtfsStopTime.getTripId(),
					gtfsStopTime.getStopId());
			gtfsStopTimesMap.put(key, gtfsStopTime);
		}

		return gtfsStopTimesMap;
	}

	/**
	 * Creates set containing the trip ID of all trips that are defined in the
	 * GTFS frequencies.txt file.
	 * 
	 * @param gtfsDirectoryName
	 *            Where to find the GTFS frequencies.txt file
	 * @return Set of all trips that are frequency based.
	 */
	private static Set<String> getFrequencyBasedTrips(String gtfsDirectoryName) {
		// Read in frequencies.txt file
		logger.info("Reading in frequencies.txt file...");

		GtfsFrequenciesReader frequenciesReader = 
				new GtfsFrequenciesReader(gtfsDirectoryName);
		List<GtfsFrequency> gtfsFrequencies = frequenciesReader.get();

		Set<String> gtfsFrequencyTrips = new HashSet<String>();
		for (GtfsFrequency gtfsFrequency : gtfsFrequencies) {
			gtfsFrequencyTrips.add(gtfsFrequency.getTripId());
		}

		return gtfsFrequencyTrips;
	};

	/**
	 * Returns trip if trip specified is frequency based as defined by the GTFS
	 * frequencies.txt file.
	 * 
	 * @param tripId
	 * @return
	 */
	private boolean isTripFrequencyBased(String tripId) {
		return gtfsFrequencyBasedTrips.contains(tripId);
	}

	/**
	 * For use in the sub-maps of arrivalTimesFromDbByRouteByTripStopMap and
	 * departureTimesFromDbByRouteByTripStopMap.
	 * 
	 * The key is simply tripId + stopId. Previously used tripId + "=" + stopId
	 * but trying to make things as efficient as possible. This might not
	 * actually be a good idea since it could make debugging a bit more
	 * difficult.
	 * 
	 * @param tripId
	 * @param stopId
	 * @return
	 */
	private static TripStopKey getTripStopKey(String tripId, String stopId) {
		// Return the key. Use intern() on the strings because will be often
		// repeating trips and stops and don't need many copies of each one.
		return new TripStopKey(tripId.intern(), stopId.intern());
	}

	/**
	 * For adding a new arrival/departure time to either
	 * arrivalTimesFromDbByRouteByTripStopMap or
	 * departureTimesFromDbByRouteByTripStopMap that is passed in.
	 * 
	 * @param timesByTripMap
	 *            map keyed on routeId containing a sub-map
	 * @param ad
	 */
	private void addArrivalDepartureToMap(
			Map<String, Map<TripStopKey, List<Integer>>> timesByTripMap,
			ArrivalDeparture ad) {
		// Get the existing sub map for the routeId
		String routeKey = ad.getRouteId();
		Map<TripStopKey, List<Integer>> timesByTripSubMap = 
				timesByTripMap.get(routeKey);

		// If the sub map for the routeId not created yet, then create it
		if (timesByTripSubMap == null) {
			timesByTripSubMap = new HashMap<TripStopKey, List<Integer>>();
			timesByTripMap.put(routeKey, timesByTripSubMap);
		}

		// Add the new arrival/departure to the sub map
		addArrivalDepartureToSubMap(timesByTripSubMap, ad);
	}

	/**
	 * Adds the arrival/departure time to the sub-map that actually contains the
	 * list of arrival/departure times.
	 * <p>
	 * For frequency based trips need the initial departure time for the trip.
	 * If that time is not available for a frequency based trip then this
	 * ArrivalDeparture will be ignored.
	 * 
	 * @param timesByTripMap
	 * @param ad
	 */
	private void addArrivalDepartureToSubMap(
			Map<TripStopKey, List<Integer>> timesByTripMap, 
			ArrivalDeparture ad) {
		// Get the existing list of times for the trip/stop
		TripStopKey key = getTripStopKey(ad.getTripId(), ad.getStopId());
		int arrDepSecsIntoDay = 
				timeForUsingCalendar.getSecondsIntoDay(ad.getDate());

		// If frequency based then need to subtract trip start time
		int timeWithRespectToTripStart = arrDepSecsIntoDay;
		if (isTripFrequencyBased(ad.getTripId())) {
			Integer terminalDepartureTimeSecs = getTerminalDepartureTime(ad);
			if (terminalDepartureTimeSecs == null)
				return;

			// If the trip start time is after the arrival/departure time
			// then we definitely have a problem. It most likely means that
			// got multiple arrival/departures for particular vehicle/trip/stop.
			// Ignore such data since there is definitely a problem.
			if (terminalDepartureTimeSecs > arrDepSecsIntoDay) {
				logger.error("Got an arrival or departure before the start "
						+ "of the trip. This of course indicates a problem. "
						+ "Therefore this arrival/departure won't be used as "
						+ "part of the stats. Trip start time={}. {}",
						Time.timeOfDayStr(terminalDepartureTimeSecs), ad);
				// Ignore this data point
				return;
			}

			// Subtract the terminal departure time. Initially simply used
			// ArrivalDeparture.getTripStartTime() but that is the time vehicle
			// would leave if exactly following the frequency exact time of
			// departure. But that of course is not nearly as good as using the
			// measured departure time for the terminal.
			timeWithRespectToTripStart -= terminalDepartureTimeSecs;

		}

		// If list of times for the trip/stop doesn't exist, yet create it
		List<Integer> times = timesByTripMap.get(key);
		if (times == null) {
			// Create the array that the times go into. Initialize size of list
			// to 5 instead of the default of 10 so that it uses less memory.
			// 5 is ideal because it is smaller but if process two weeks of
			// data then it will be expanded to 10 for the 10 weekdays. If
			// would use 4 then it would be expanded to 8 and then 16, actually
			// taking up more space than if the default size of 10 were used.
			times = new ArrayList<Integer>(5);
			timesByTripMap.put(key, times);
		}

		// Add the new time to the list for the trip/stop
		times.add(timeWithRespectToTripStart);
	}

	/**
	 * Determines based on the ArrivalDeparture parameter the appropriate key to
	 * use for the departureTimesFromTerminalMap.
	 * <p>
	 * Note: this does not work if trip spans midnight. This isn't important for
	 * now but could be if have frequencies based trips that do span midnight.
	 * 
	 * @param arrDep
	 * @return
	 */
	private TerminalDeparturesKey getTerminalDeparturesKey(
			ArrivalDeparture arrDep) {
		return new TerminalDeparturesKey(arrDep.getVehicleId().intern(), arrDep
				.getBlockId().intern(),
				timeForUsingCalendar.getDayOfYear(arrDep.getDate()));
	}

	/**
	 * If handling departures and this trip is defined as a frequency then need
	 * store the departure time of the first stop in the trip in a map so can
	 * use it for determine the arrival/departure times relative to the start of
	 * the trip.
	 * 
	 * @param arrDep
	 */
	private void handleMapOfTerminalStartTimes(ArrivalDeparture arrDep) {
		// If it is a departure from a terminal for a frequency based...
		if (arrDep.isDeparture() && arrDep.getStopPathIndex() == 0
				&& isTripFrequencyBased(arrDep.getTripId())) {
			// It is terminal departure time so add it to map
			int departureTimeSecsIntoDay = 
					timeForUsingCalendar.getSecondsIntoDay(arrDep.getDate());
			TerminalDeparturesKey mapKey = getTerminalDeparturesKey(arrDep);
			departureTimesFromTerminalMap.put(mapKey, departureTimeSecsIntoDay);
		}
	}

	/**
	 * Returns the start time of the departure from the terminal for the trip
	 * associated with the ArrivalDeparture.
	 * 
	 * @param arrDep
	 *            Specifies the trip that should get terminal departure time
	 *            for.
	 * @return Departure of the trip specified by arrDep parameter
	 */
	private Integer getTerminalDepartureTime(ArrivalDeparture arrDep) {
		TerminalDeparturesKey mapKey = getTerminalDeparturesKey(arrDep);
		return departureTimesFromTerminalMap.get(mapKey);
	}

	/**
	 * Reads in the arrival/departure times from the db and returns a map
	 * containing just the arrival/departure times.
	 * departureTimesFromDbByRouteByTripStopMap. Reads in data in batches
	 * because that is more memory efficient than reading in everything at once
	 * when there is a large amount of data. Also, batching is much quicker than
	 * using the iterator method since that does a separate query for each row.
	 * Found a batch size of at least 50k is much more efficient than 10k by
	 * about a factor of 2.
	 * 
	 * @param arrivalOrDeparture
	 *            Specifies whether should read in arrivals or, instead,
	 *            departures.
	 * @return Big map keyed on route of data. The sub-map is keyed on on a
	 *         TripStopKey using tripId+stopId and contains list of all of the
	 *         times (arrivals or departures) for that trip/stop for the route.
	 *         The times are seconds into the day. If frequencies are being used
	 *         then the times are relative to the trip start time, because that
	 *         is how frequency based data is specified in the stop_times.txt
	 *         file.
	 */
	private Map<String, Map<TripStopKey, List<Integer>>> 
		readInArrivalsOrDeparturesFromDb(
			ArrivalsOrDepartures arrivalOrDeparture) {
		logger.info("Reading {} from db for projectId={} for beginDate={} "
				+ "and endDate={}", arrivalOrDeparture, projectId, beginTime,
				endTime);

		Map<String, Map<TripStopKey, List<Integer>>> 
			arrivalDeparatureTimesFromDbByRouteByTripStopMap = 
				new HashMap<String, Map<TripStopKey, List<Integer>>>();

		// Go through all the arrival/departure data and put it into a map
		// that just keeps track of arrival/departure times for trip/stops.
		try {
			// Use two levels of batching to be efficient. At the low level
			// only read in 50k rows at a time so that never read in too much
			// at once. This way won't run out of memory. But this type of
			// batching where we specify a firstResult and a batchSize can be
			// inefficient when firstResult becomes large because the database
			// still has to process a huge amount of data in order to return
			// the proper batch. So also just dealing with a day at a time by
			// dividing the beginTime and endTime into 1 day chunks.
			long batchBeginTime = beginTime.getTime();
			long batchEndTime = beginTime.getTime() + Time.MS_PER_DAY;
			while (batchBeginTime < endTime.getTime()) {
				logger.info("Reading in a days worth of {} data "
						+ "for between {} and {}", arrivalOrDeparture,
						new Date(batchBeginTime), new Date(batchEndTime));

				// For keeping track of which rows should be returned by the
				// batch.
				int firstResult = 0;
				// Batch size of 50k found to be significantly faster than 10k,
				// by about a factor of 2. So want to use as large a value as
				// possible without running out of memory. Found that with
				// 500,000
				// can still read in all data with default heap size of 1G.
				// Batch size of 650,000 seems to complete halt the process with
				// only 1G of heap.
				int batchSize = 500000; // Also known as maxResults
				// The temporary list for the loop that contains a batch of
				// results
				List<ArrivalDeparture> arrDepBatchList;
				// Read in batch of 50k rows of data and process it
				do {
					// Note: I tried adding a "ORDER BY time" clause to see
					// if that would speed things up when doing multiple batches
					// but it only served to slow things down.
					arrDepBatchList = ArrivalDeparture
							.getArrivalsDeparturesFromDb(projectId, new Date(
									batchBeginTime), new Date(batchEndTime),
									null, // SQL clause
									firstResult, batchSize, arrivalOrDeparture);

					for (ArrivalDeparture arrDep : arrDepBatchList) {
						// If handling departures and this trip is defined as a
						// frequency then need store the departure time of the
						// first stop in the trip in a map so can use it for
						// determine the arrival/departure times relative to the
						// start of the trip.
						handleMapOfTerminalStartTimes(arrDep);

						// Add arrival/departure time to appropriate map
						addArrivalDepartureToMap(
								arrivalDeparatureTimesFromDbByRouteByTripStopMap,
								arrDep);
					}

					logger.info("Read in total of {} {}", firstResult
							+ arrDepBatchList.size(), arrivalOrDeparture);

					// Update firstResult for reading next batch of data
					firstResult += batchSize;
				} while (arrDepBatchList.size() == batchSize);

				// Get ready to read in chunk of data for the next day
				batchBeginTime += Time.MS_PER_DAY;
				batchEndTime += Time.MS_PER_DAY;
				if (batchEndTime > endTime.getTime())
					batchEndTime = endTime.getTime();
			}
		} catch (Exception e) {
			logger.error("Exception occurred when reading arrivals/departure "
					+ "from db", e);
		}

		return arrivalDeparatureTimesFromDbByRouteByTripStopMap;
	}

	/**
	 * For containing the results of processing AVL based arrival/departure
	 * times as part of determining more accurate schedule times.
	 */
	public static class Stats {
		// Seconds into day
		public int bestValue;
		// The arrival/departure times. Ones from the db that are far from the
		// schedule time or the mean time are filtered out and therefore not
		// included.
		public int filteredTimesArray[];
		// The arrival/departure times from the db. Includes even the filtered
		// times.
		public int unfilteredTimesArray[];
		// The average of the filtered times
		public float mean;
		// Will be NaN if there was only a single data point for the trip/stop
		public float standardDeviation;
		// Initialize to MAX_VALUE to simplify determination of minimum
		public int min = Integer.MAX_VALUE;
		// Initialize to MIN_VALUE to simplify determination of maximum
		public int max = Integer.MIN_VALUE;
	}

	/**
	 * For the trip/stop specified by gtfsStopTime determines the Stats object
	 * which contains mean, standard deviation, min, max, and number of data
	 * points. Sets everything in the Stats structure except for the bestValue
	 * member.
	 * 
	 * @param timesFromDb
	 *            data for particular trip/stop. This list will be modified by
	 *            filtering out data that is too far from the schedule time or
	 *            too far from the mean.
	 * @param originalGtfsStopTime
	 *            The original data from stop_times.txt for this trip/stop. Used
	 *            for filtering out outliers. Can be null when using test data
	 *            instead of data from stop_times.txt.
	 * @param arrivalsOrDepartures
	 *            specifies whether should handle as arrivals or departures
	 * @return Stats object containing the statistics for the trip/stop or null
	 *         if there is no data.
	 */
	private Stats getStatisticsForTripStop(List<Integer> timesFromDb,
			GtfsStopTime originalGtfsStopTime,
			ArrivalsOrDepartures arrivalsOrDepartures) {
		// If no data for this trip/stop then return null
		if (timesFromDb == null)
			return null;

		// There is data for this trip/stop so process it. Create the Stats
		// object and start filling it out.
		Stats results = new Stats();
		results.unfilteredTimesArray = Statistics.toArray(timesFromDb);

		// Determine mean before filtering out data
		double mean = Statistics.getMean(results.unfilteredTimesArray);

		// Filter out outliers from timesFromDb and put into filteredTimesFromDb
		List<Integer> filteredTimesFromDb = new ArrayList<Integer>(timesFromDb);
		boolean timeFilteredOut = false;
		Iterator<Integer> iterator = filteredTimesFromDb.iterator();
		Integer originalScheduleTime = 0;
		if (originalGtfsStopTime != null) {
			originalScheduleTime = 
					arrivalsOrDepartures == ArrivalsOrDepartures.ARRIVALS ? 
							originalGtfsStopTime.getArrivalTimeSecs() : 
								originalGtfsStopTime.getDepartureTimeSecs();
		}
		while (iterator.hasNext()) {
			int time = iterator.next();

			// If time is too far away from mean then don't use it
			if (Math.abs(time - mean) > allowableDifferenceFromMeanSecs) {
				timeFilteredOut = true;
				iterator.remove();
				logger.debug(
						"For trip={} stop={} filtering out {} time={} because "
								+ "it is {} seconds from the mean={} which is futher "
								+ "awat than the allowable={} secs.",
						originalGtfsStopTime == null ? "NA"
								: originalGtfsStopTime.getTripId(),
						originalGtfsStopTime == null ? "NA"
								: originalGtfsStopTime.getStopId(),
						arrivalsOrDepartures, Time.timeOfDayStr(time),
						StringUtils.oneDigitFormat(Math.abs(time - mean)), Time
								.timeOfDayStr(Math.round(mean)),
						allowableDifferenceFromMeanSecs);
			}

			// If time is too far away from original time in stop_times.txt
			// then don't use it.
			else if (Math.abs(time - originalScheduleTime) > allowableDifferenceFromOriginalTimeSecs) {
				timeFilteredOut = true;
				iterator.remove();
				logger.debug(
						"For trip={} stop={} filtering out {} time={} because "
								+ "it is {} seconds from the original time={} which is "
								+ "further away than the allowable={} secs.",
						originalGtfsStopTime == null ? "NA"
								: originalGtfsStopTime.getTripId(),
						originalGtfsStopTime == null ? "NA"
								: originalGtfsStopTime.getStopId(),
						arrivalsOrDepartures, Time.timeOfDayStr(time), time
								- originalScheduleTime, Time
								.timeOfDayStr(originalScheduleTime),
						allowableDifferenceFromOriginalTimeSecs);
			}
		}

		// If no data points left due to filtering then simply return null
		if (filteredTimesFromDb.size() == 0)
			return null;

		// If filtered out any times then need to update timesArray and the mean
		if (timeFilteredOut) {
			results.filteredTimesArray = 
					Statistics.toArray(filteredTimesFromDb);
			results.mean = 
					(float) Statistics.getMean(results.filteredTimesArray);
		} else {
			results.filteredTimesArray = results.unfilteredTimesArray;
			results.mean = (float) mean;
		}

		// Determine the standard deviation using the filtered times
		double doubleArray[] = Statistics
				.toDoubleArray(results.filteredTimesArray);
		results.standardDeviation = (float) Statistics
				.getSampleStandardDeviation(doubleArray, mean);

		// Determine min and max using the filtered times
		for (int time : results.filteredTimesArray) {
			// Deal with min and max
			if (time < results.min)
				results.min = time;
			if (time > results.max)
				results.max = time;
		}

		// Return the results for this trip/stop
		return results;
	}

	/**
	 * Goes through the Collection of Stats objects passed in and determines the
	 * fraction (0.0 - 1.0) that are before the number of standardDeviations
	 * specified before the mean.
	 * 
	 * @param standardDeviations
	 *            The number of standard of deviations to subtract from the mean
	 *            time for determining fraction of arrivals/departures that are
	 *            early.
	 * @param statistics
	 *            Contains the times, the mean, and the standard deviation used
	 *            for the calculations for all of the routes.
	 * @return The fraction of arrival/departure times that are early
	 */
	private static double determineFractionEarly(double standardDeviations,
			Collection<Stats> statistics) {
		int totalTimes = 0;
		int totalEarly = 0;
		for (Stats stats : statistics) {
			if (stats.filteredTimesArray.length >= 2) {
				totalTimes += stats.filteredTimesArray.length;
				for (int time : stats.filteredTimesArray) {
					double allowableTime = stats.mean - 
							stats.standardDeviation	* standardDeviations;
					if (time < allowableTime) {
						++totalEarly;
					}
				}
			}
		}

		// Return result
		return (double) totalEarly / totalTimes;
	}

	/**
	 * For the route specified first determines the basic stats including the
	 * mean and standard deviation. Then determines how many standard deviations
	 * to use for this route such that get approximately the desired fraction of
	 * times being earlier than the mean minus the number of standard deviations
	 * for this trip/stop. Puts the resulting Stats objects that now contain the
	 * best arrival/departure value to use into statsResultsByTripStopMap. This
	 * is done on a per route basis since it is expected that the distribution
	 * of the stats will vary between routes.
	 * 
	 * @param originalGtfsStopTimes
	 *            original data from stop_times.txt file so that can filter out
	 *            outliers. Can be null for testing. Map is keyed on trip/stops
	 *            and contains GtfsStopTimes.
	 * @param timesFromDbByTripStopForRouteSubMap
	 *            the arrival/departure times for the route
	 * @param statsResultsByTripStopMap
	 *            for returning the results
	 * @param routeId
	 *            for logging
	 * @param arrivalsOrDepartures
	 *            specifies whether should handle as arrivals or departures
	 */
	private void determineStatsForRoute(
			Map<TripStopKey, GtfsStopTime> originalGtfsStopTimes,
			Map<TripStopKey, List<Integer>> timesFromDbByTripStopForRouteSubMap,
			Map<TripStopKey, Stats> statsResultsByTripStopMap, String routeId,
			ArrivalsOrDepartures arrivalsOrDepartures) {
		logger.debug("Processing {} data for routeId={}", arrivalsOrDepartures,
				routeId);

		// For the route get all of the statistics including the mean and
		// standard deviation for each stop
		Set<TripStopKey> tripStopKeysForRouteFromDb = 
				timesFromDbByTripStopForRouteSubMap.keySet();
		Map<TripStopKey, Stats> statsForRoute = new HashMap<TripStopKey, Stats>(
				tripStopKeysForRouteFromDb.size());
		for (TripStopKey tripStopKey : tripStopKeysForRouteFromDb) {
			List<Integer> timesFromDb = 
					timesFromDbByTripStopForRouteSubMap.get(tripStopKey);
			GtfsStopTime originalGtfsStopTime = originalGtfsStopTimes == null ? 
					null : originalGtfsStopTimes.get(tripStopKey);
			Stats statsForTripStop = getStatisticsForTripStop(timesFromDb,
					originalGtfsStopTime, arrivalsOrDepartures);
			if (statsForTripStop != null) {
				statsForRoute.put(tripStopKey, statsForTripStop);
			}
		}

		// Iterate to find the best number of standard deviations to use to
		// get the fraction of. The result of this section of code is that
		// currentStdDevs will be set to the number of standard deviations to
		// use in order to get the desired number of early arrival/departures
		// for the current route.
		double lowStdDevs = 0.0;
		double highStdDevs = 2.0;
		double currentStdDevs = 1.0;
		for (int iteration = 0; iteration < NUMBER_ITERATIONS; ++iteration) {
			double fractionEarly = determineFractionEarly(currentStdDevs,
					statsForRoute.values());
			logger.debug("For iteration={} currentStdDevs={} "
					+ "desiredFractionEarly={} fractionEarly={}", iteration,
					currentStdDevs, desiredFractionEarly,
					StringUtils.threeDigitFormat(fractionEarly));
			if (fractionEarly < desiredFractionEarly) {
				// Need to use lower std dev to get desired results
				highStdDevs = currentStdDevs;
				currentStdDevs = (currentStdDevs + lowStdDevs) / 2.0;
			} else {
				// Need to use higher std dev to get desired results
				lowStdDevs = currentStdDevs;
				currentStdDevs = (currentStdDevs + highStdDevs) / 2.0;
			}
		}
		double standardDeviationsToUse = currentStdDevs;

		// Now that we know how many standard deviations to use to get
		// approximately the desired fraction of early arrival/departures
		// update the Stats object with the best time and put the Stats
		// object into the results map.
		for (TripStopKey tripStopKey : tripStopKeysForRouteFromDb) {
			// The best arrival/departure time value to use is the mean
			// minus the desired standard deviation if the std dev is
			// valid (there was more than 1 data point).
			Stats stats = statsForRoute.get(tripStopKey);

			// If there is no data for the trip/stop then can't process
			// it. This can happen if all the data points were filtered
			// out for the trip/stop.
			if (stats == null)
				continue;

			double bestValue = stats.mean;
			if (!Float.isNaN(stats.standardDeviation))
				bestValue -= stats.standardDeviation * standardDeviationsToUse;
			stats.bestValue = (int) Math.round(bestValue);

			// Add the stats for this trip/stop to the map of stats that is to
			// be returned.
			statsResultsByTripStopMap.put(tripStopKey, stats);
		}
	}

	/**
	 * Reads in times from database and fills in statsResultsByTripStopMap by
	 * determining the best arrival/departure time to use. Does this on a per
	 * route basis.
	 * 
	 * @param originalGtfsStopTimes
	 *            original data from stop_times.txt file so that can filter out
	 *            outliers. Can be null for testing. Map is keyed on trip/stops
	 *            and contains GtfsStopTimes.
	 * @param arrivalsOrDepartures
	 *            specifies whether should handle as arrivals or departures
	 * @return map containing stats for each trip/stop for all routes
	 */
	private Map<TripStopKey, Stats> determineStatsForRoutes(
			Map<TripStopKey, GtfsStopTime> originalGtfsStopTimes,
			ArrivalsOrDepartures arrivalsOrDepartures) {
		// For returning results
		Map<TripStopKey, Stats> statsResultsByTripStopMap = 
				new HashMap<TripStopKey, Stats>();

		// Read the arrival/departure times from the db
		Map<String, Map<TripStopKey, List<Integer>>>
			timesFromDbByRoutesByTripStopMap = 
				readInArrivalsOrDeparturesFromDb(arrivalsOrDepartures);

		// Handle the arrival/departure times for each route
		Set<String> routeIds = timesFromDbByRoutesByTripStopMap.keySet();
		for (String routeId : routeIds) {
			Map<TripStopKey, List<Integer>> timesByTripStopForRouteSubMap = 
					timesFromDbByRoutesByTripStopMap.get(routeId);
			determineStatsForRoute(originalGtfsStopTimes,
					timesByTripStopForRouteSubMap, statsResultsByTripStopMap,
					routeId, arrivalsOrDepartures);
		}

		return statsResultsByTripStopMap;
	}

	/**
	 * For each trip/stop in the stop_times.txt file sees if there is AVL based
	 * arrival/departure times. If there is then it is used when creating
	 * GtfsExtendedStopTime object. If no data for the trip/stop then null
	 * values will be used. The result GtfsExtendedStopTimes are then written to
	 * two files: - stop_times.txt_new which uses the standard GTFS format -
	 * stop_times.txt_extended which has additional info such as standard
	 * deviation.
	 */
	private void processDbData() {
		// Determine the arrival/departure times to use by
		// doing statistical analysis. Puts results into
		// arrivalStatsResultsByTripStopMap and
		// departureStatsResultsByTripStopMap parameters.
		// Need to process departures first because for when frequencies
		// used need to determine times relative to the start time
		// of the trip, which is the departure from the terminal.
		Map<TripStopKey, Stats> departureStatsResultsByTripStopMap = 
				determineStatsForRoutes(gtfsStopTimes, 
						ArrivalsOrDepartures.DEPARTURES);
		Map<TripStopKey, Stats> arrivalStatsResultsByTripStopMap = 
				determineStatsForRoutes(gtfsStopTimes, 
						ArrivalsOrDepartures.ARRIVALS);

		// For writing the stop_times.txt_extended file with the additional data
		String extendedFileName = gtfsDirectoryName + "/"
				+ "stop_times.txt_extended";
		logger.info("Creating the new GTFS stop_times file {} ...",
				extendedFileName);
		GtfsExtendedStopTimesWriter extendedWriter = new GtfsExtendedStopTimesWriter(
				extendedFileName);

		// Write the new stop_times.txt_new data
		String newFileName = gtfsDirectoryName + "/" + "stop_times.txt_new";
		logger.info("Creating the new GTFS stop_times file {} ...", newFileName);
		GtfsStopTimesWriter writer = new GtfsStopTimesWriter(newFileName);

		// Go through list of stop times from the GTFS stop_times.txt file
		// and create corresponding GtfsExtendedStopTimes. Need to determine
		// if first stop of trip to know if should actually modify the schedule
		// time. Therefore keeping track of previousTripId so can determine
		// when iterating over a new trip.
		logger.info("Creating GtfsExtendedStopTimes objects for all data "
				+ "from stop_times.txt file.");
		String previousTripId = null;
		for (GtfsStopTime gtfsStopTime : gtfsStopTimes.values()) {
			// Determine values to use for both arrival and departure times. The
			// Stats will be null if there was no data for the particular
			// trip/stop.
			TripStopKey tripStopKey = getTripStopKey(gtfsStopTime.getTripId(),
					gtfsStopTime.getStopId());
			Stats arrivalTimeResults = arrivalStatsResultsByTripStopMap
					.get(tripStopKey);
			Stats departureTimeResults = departureStatsResultsByTripStopMap
					.get(tripStopKey);

			// Determine if should use original arrival/departure time for
			// this stop/trip. This could be true for first stop of trip
			// where don't want to modify the scheduled departure time
			// just because drivers aren't leaving on time.
			boolean useOriginalSchedTimes = false;
			if (doNotUpdateFirstStopOfTrip) {
				// If at first stop of new trip
				if (!gtfsStopTime.getTripId().equals(previousTripId)) {
					useOriginalSchedTimes = true;
				}
			}
			previousTripId = gtfsStopTime.getTripId();

			// Create the GtfsExtendedStopTime that corresponds to the
			// GtfsStopTime from the stop_times.txt file. These values are
			// used to write the new version of the stop_times file with
			// the more accurate schedule times.
			GtfsExtendedStopTime extendedStopTime = new GtfsExtendedStopTime(
					gtfsStopTime, useOriginalSchedTimes, arrivalTimeResults,
					departureTimeResults);

			// Write the data to the stop_times files
			extendedWriter.write(extendedStopTime);
			writer.write(extendedStopTime);
		}

		// Finish up the writing of the stop_times files
		extendedWriter.close();
		writer.close();

		// Log schedule adherence results for both the original schedule and
		// for the new more accurate schedule.
		processScheduleAdherence(gtfsStopTimes,
				arrivalStatsResultsByTripStopMap,
				departureStatsResultsByTripStopMap);
	}

	/**
	 * Determines and logs schedule adherence results both using the original
	 * schedule and using the new improved schedule.
	 * 
	 * @param gtfsStopTimes
	 *            The original GtfsStopTimes read from stop_times.txt.
	 * @param arrivalStatsResultsByTripStopMap
	 *            All the arrival time results. Keyed on trip/stop.
	 * @param departureStatsResultsByTripStopMap
	 *            All the departure time results. Keyed on trip/stop.
	 */
	private void processScheduleAdherence(
			Map<TripStopKey, GtfsStopTime> gtfsStopTimes,
			Map<TripStopKey, Stats> arrivalStatsResultsByTripStopMap,
			Map<TripStopKey, Stats> departureStatsResultsByTripStopMap) {
		logger.info("Processing schedule adherence information...");

		// For keeping track of schedule adherence results while iterating
		// through all of the data
		int numberEarlyOrigSchedule = 0;
		int numberLateOrigSchedule = 0;
		int numberEarlyNewSchedule = 0;
		int numberLateNewSchedule = 0;
		int totalDataPoints = 0;

		// Go through list of stop times from the GTFS stop_times.txt file
		// and create corresponding GtfsExtendedStopTimes. Need to know if
		// last stop of trip so that will use arrival time for schedule
		// adherence instead of departure time. Therefore when iterating
		// across all the GtfsStopTime data need to also keep track of the
		// next ones. Therefore iteration is a bit more complicated than usual.
		Iterator<GtfsStopTime> gtfsStopTimesIterator = 
				gtfsStopTimes.values().iterator();
		GtfsStopTime currentGtfsStopTime = null;
		GtfsStopTime nextGtfsStopTime = gtfsStopTimesIterator.next();
		do {
			// Determine previous, current, and next GtfsStopTime so can
			// handle specially first and last stop of trips.
			currentGtfsStopTime = nextGtfsStopTime;
			if (gtfsStopTimesIterator.hasNext())
				nextGtfsStopTime = gtfsStopTimesIterator.next();
			else
				nextGtfsStopTime = null;

			// Determine the stats, the original schedule time, and the new
			// schedule time. If last stop of trip then use arrival time
			// instead of departure time.
			Stats stats;
			Integer originalScheduleTime;
			TripStopKey tripStopKey = getTripStopKey(
					currentGtfsStopTime.getTripId(),
					currentGtfsStopTime.getStopId());
			if (nextGtfsStopTime == null
					|| !currentGtfsStopTime.getTripId().equals(
							nextGtfsStopTime.getTripId())) {
				// Use arrival time because last stop of trip
				stats = arrivalStatsResultsByTripStopMap.get(tripStopKey);
				originalScheduleTime = currentGtfsStopTime.getArrivalTimeSecs();
			} else {
				// Use normal departure time
				stats = departureStatsResultsByTripStopMap.get(tripStopKey);
				originalScheduleTime = currentGtfsStopTime
						.getDepartureTimeSecs();
			}
			int newScheduleTime = stats != null ? stats.bestValue
					: originalScheduleTime;

			// Keep count of number vehicles early and late for both the
			// old schedule and the new more accurate schedule so can log
			// schedule adherence totals. This way can see how much
			// schedule adherence will be improved using the new more
			// accurate schedule.
			if (stats != null && originalScheduleTime != null) {
				totalDataPoints += stats.unfilteredTimesArray.length;
				for (int time : stats.unfilteredTimesArray) {
					if (time < originalScheduleTime - allowableEarlySecs)
						++numberEarlyOrigSchedule;
					else if (time > originalScheduleTime + allowableLateSecs)
						++numberLateOrigSchedule;
					if (time < newScheduleTime - allowableEarlySecs)
						++numberEarlyNewSchedule;
					else if (time > newScheduleTime + allowableLateSecs)
						++numberLateNewSchedule;
				}
			}
		} while (nextGtfsStopTime != null);

		// Log schedule adherence results for both the original schedule and
		// for the new more accurate schedule.
		double percentageOnTimeOrigSched = 100.0
				* (totalDataPoints - numberEarlyOrigSchedule - numberLateOrigSchedule)
				/ totalDataPoints;
		logger.info("For original schedule time numDataPoints={} early={} "
				+ "late={} percentageOnTime={}", totalDataPoints,
				numberEarlyOrigSchedule, numberLateOrigSchedule,
				StringUtils.twoDigitFormat(percentageOnTimeOrigSched));
		double percentageOnTimeNewSched = 100.0
				* (totalDataPoints - numberEarlyNewSchedule - numberLateNewSchedule)
				/ totalDataPoints;
		logger.info("For new schedule time numDataPoints={} early={} "
				+ "late={} percentageOnTime={}", totalDataPoints,
				numberEarlyNewSchedule, numberLateNewSchedule,
				StringUtils.twoDigitFormat(percentageOnTimeNewSched));
	}

	/**
	 * Reads original stop_times.txt file, reads in arrival/departures from the
	 * database, processes the arrival/departure info to determine more accurate
	 * schedule times, and writes the results to new stop_times files.
	 */
	public void process() {
		// Determine the more accurate schedule times
		logger.info("Processing the arrival/departure times to determine "
				+ "the more accurate schedule times and then writing the "
				+ "new stop_times files...");
		processDbData();

		// Log that done so can see how long it took
		logger.info("Done creating new GTFS stop_times files.");
	}

	/**
	 * Just for testing...
	 * 
	 * Note: convenient website for determining standard deviation is at
	 * http://www.mathsisfun.com/data/standard-deviation-calculator.html
	 * 
	 * @param args
	 */
	@SuppressWarnings("unused")
	public static void main(String args[]) {
		Map<String, Map<String, List<Integer>>> arrivalTimesFromDbByRouteByTripStopMap = new HashMap<String, Map<String, List<Integer>>>();

		Map<String, List<Integer>> timesByTripStopMap = new HashMap<String, List<Integer>>();

		Integer array1[] = { 2, 4, 4, 4, 4, 5, 5, 7, 9 };
		List<Integer> times1 = Arrays.asList(array1);
		timesByTripStopMap.put("tripStop1", times1);

		Integer array2[] = { 21, 32, 43, 44, 50, 51, 50, 72, 91 };
		List<Integer> times2 = Arrays.asList(array2);
		timesByTripStopMap.put("tripStop2", times2);

		Integer array3[] = { 11, 41, 42, 46, 51, 53, 52, 86, 97, 101 };
		List<Integer> times3 = Arrays.asList(array3);
		timesByTripStopMap.put("tripStop3", times3);

		arrivalTimesFromDbByRouteByTripStopMap
				.put("route1", timesByTripStopMap);

		ScheduleStatistics scheduleStats = new ScheduleStatistics(null, null,
				null, null, null, 0.25, // desiredFractionEarly
				5, // allowableDifferenceFromMeanSecs
				5, // allowableDifferenceFromOriginalTimeSecs
				false, // doNotUpdateFirstStopOfTrip
				60, // allowable early seconds for sched adherence
				300); // allowable late seconds for sched adherence

		// scheduleStats.determineStatsForRoutes(
		// null, // originalGtfsStopTimes set to null for testing
		// arrivalTimesFromDbByRouteByTripStopMap,
		// ArrivalsOrDepartures.ARRIVALS);
	}
}
