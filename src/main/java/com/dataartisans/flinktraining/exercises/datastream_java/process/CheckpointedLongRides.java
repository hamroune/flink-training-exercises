/*
 * Copyright 2017 data Artisans GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dataartisans.flinktraining.exercises.datastream_java.process;

import com.dataartisans.flinktraining.exercises.datastream_java.basics.RideCleansing;
import com.dataartisans.flinktraining.exercises.datastream_java.datatypes.TaxiRide;
import com.dataartisans.flinktraining.exercises.datastream_java.sources.CheckpointedTaxiRideSource;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.TimerService;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;

import java.util.concurrent.TimeUnit;

/**
 * Java reference implementation for the "Long Ride Alerts" exercise of the Flink training
 * (http://training.data-artisans.com).
 *
 * The goal for this exercise is to emit START events for taxi rides that have not been matched
 * by an END event during the first 2 hours of the ride.
 *
 * This version is setup for checkpointing and fault recovery.
 *
 * Parameters:
 * -input path-to-input-file
 *
 */
public class CheckpointedLongRides {
	public static void main(String[] args) throws Exception {

		ParameterTool params = ParameterTool.fromArgs(args);
		final String input = params.getRequired("input");
		final int servingSpeedFactor = 1800; // 30 minutes worth of events are served every second

		// set up streaming execution environment
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

		// set up checkpointing
		env.setStateBackend(new FsStateBackend("file:///tmp/checkpoints"));
		env.enableCheckpointing(1000);
		env.setRestartStrategy(RestartStrategies.fixedDelayRestart(60, Time.of(10, TimeUnit.SECONDS)));

		DataStream<TaxiRide> rides = env.addSource(new CheckpointedTaxiRideSource(input, servingSpeedFactor));

		DataStream<TaxiRide> longRides = rides
				.filter(new RideCleansing.NYCFilter())
				.keyBy("rideId")
				.process(new MatchFunction());

		longRides.print();

		env.execute("Long Taxi Rides (checkpointed)");
	}

	public static class MatchFunction extends ProcessFunction<TaxiRide, TaxiRide> {
		// keyed, managed state
		// holds an END event if the ride has ended, otherwise a START event
		private ValueState<TaxiRide> rideState;

		@Override
		public void open(Configuration config) {
			ValueStateDescriptor<TaxiRide> startDescriptor =
					new ValueStateDescriptor<>("saved ride", TaxiRide.class);
			rideState = getRuntimeContext().getState(startDescriptor);
		}

		@Override
		public void processElement(TaxiRide ride, Context context, Collector<TaxiRide> out) throws Exception {
			TimerService timerService = context.timerService();

			if (ride.isStart) {
				// the matching END might have arrived first (out of order); don't overwrite it
				if (rideState.value() == null) {
					rideState.update(ride);
				}
			} else {
				rideState.update(ride);
			}

			timerService.registerEventTimeTimer(ride.getEventTime() + 120 * 60 * 1000);
		}

		@Override
		public void onTimer(long timestamp, OnTimerContext context, Collector<TaxiRide> out) throws Exception {
			TaxiRide savedRide = rideState.value();
			if (savedRide != null && savedRide.isStart) {
				out.collect(savedRide);
			}
			rideState.clear();
		}
	}
}