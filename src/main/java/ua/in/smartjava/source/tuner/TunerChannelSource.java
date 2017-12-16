/*******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2017 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 ******************************************************************************/
package ua.in.smartjava.source.tuner;

import ua.in.smartjava.channel.heartbeat.Heartbeat;
import ua.in.smartjava.dsp.filter.FilterFactory;
import ua.in.smartjava.dsp.filter.Window.WindowType;
import ua.in.smartjava.dsp.filter.cic.ComplexPrimeCICDecimate;
import ua.in.smartjava.dsp.mixer.Oscillator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.in.smartjava.sample.Buffer;
import ua.in.smartjava.sample.Listener;
import ua.in.smartjava.sample.OverflowableTransferQueue;
import ua.in.smartjava.sample.complex.Complex;
import ua.in.smartjava.sample.complex.ComplexBuffer;
import ua.in.smartjava.sample.real.IOverflowListener;
import ua.in.smartjava.source.ComplexSource;
import ua.in.smartjava.source.SourceException;
import ua.in.smartjava.source.tuner.frequency.FrequencyChangeEvent;
import ua.in.smartjava.source.tuner.frequency.FrequencyChangeEvent.Event;
import ua.in.smartjava.source.tuner.frequency.IFrequencyChangeListener;
import ua.in.smartjava.source.tuner.frequency.IFrequencyChangeProcessor;
import ua.in.smartjava.source.tuner.frequency.IFrequencyChangeProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TunerChannelSource extends ComplexSource implements IFrequencyChangeProcessor, Listener<ComplexBuffer>
{
    private final static Logger mLog = LoggerFactory.getLogger(TunerChannelSource.class);

    //Maximum number of filled buffers for the blocking queue
    private static final int BUFFER_MAX_CAPACITY = 300;

    //Threshold for resetting ua.in.smartjava.buffer overflow condition
    private static final int BUFFER_OVERFLOW_RESET_THRESHOLD = 100;

    private static int CHANNEL_RATE = 48000;
    private static int CHANNEL_PASS_FREQUENCY = 12000;
    private static final Heartbeat HEARTBEAT = new Heartbeat();

    private OverflowableTransferQueue<ComplexBuffer> mBuffer;

    private Tuner mTuner;
    private TunerChannel mTunerChannel;
    private Oscillator mMixer;
    private ComplexPrimeCICDecimate mDecimationFilter;
    private Listener<ComplexBuffer> mListener;
    private Listener<Heartbeat> mHeartbeatListener;
    private IFrequencyChangeProcessor mFrequencyChangeProcessor;
    private DownstreamProcessor mDownstreamFrequencyEventProcessor = new DownstreamProcessor();
    private ScheduledFuture<?> mTaskHandle;

    private long mTunerFrequency = 0;
    private int mTunerSampleRate;
    private int mChannelFrequencyCorrection = 0;

    private DecimationProcessor mDecimationProcessor = new DecimationProcessor();

    private AtomicBoolean mRunning = new AtomicBoolean();
    private boolean mExpended = false;

    /**
     * Provides a Digital Drop Channel (DDC) to decimate the IQ output from a
     * tuner down to a 48 kHz IQ ua.in.smartjava.channel rate.
     *
     * Note: this class can only be used once (started and stopped) and a new
     * tuner ua.in.smartjava.channel ua.in.smartjava.source must be requested from the tuner once this object
     * has been stopped.  This is because channels are managed dynamically and
     * center tuned frequency may have changed since this ua.in.smartjava.source was obtained
     * and thus the tuner might no longer be able to ua.in.smartjava.source this ua.in.smartjava.channel once it
     * has been stopped.
     *
     * @param tuner to obtain wideband IQ samples from
     * @param tunerChannel specifying the center frequency for the DDC
     * @throws RejectedExecutionException if the thread pool manager cannot
     *                                    accept the decimation processing task
     * @throws SourceException            if the tuner has an issue providing IQ samples
     */
    public TunerChannelSource(Tuner tuner, TunerChannel tunerChannel) throws RejectedExecutionException, SourceException
    {
        mTuner = tuner;
        mTunerChannel = tunerChannel;
        mTuner.getTunerController().addListener((IFrequencyChangeProcessor) this);
        mTunerFrequency = mTuner.getTunerController().getFrequency();

        mBuffer = new OverflowableTransferQueue<>(BUFFER_MAX_CAPACITY, BUFFER_OVERFLOW_RESET_THRESHOLD);

	    /* Setup the frequency translator to the current ua.in.smartjava.source frequency */
        long frequencyOffset = mTunerFrequency - mTunerChannel.getFrequency();

        mMixer = new Oscillator(frequencyOffset, mTuner.getTunerController().getSampleRate());

		/* Fire a ua.in.smartjava.sample rate change event to setup the decimation chain */
        frequencyChanged(new FrequencyChangeEvent(Event.NOTIFICATION_SAMPLE_RATE_CHANGE,
            mTuner.getTunerController().getSampleRate()));
    }

    /**
     * Overrides the default ua.in.smartjava.source overflow listener management to delegate responsibility to the overflow ua.in.smartjava.buffer
     */
    @Override
    public void setOverflowListener(IOverflowListener listener)
    {
        mBuffer.setOverflowListener(listener);
    }

    public void start(ScheduledExecutorService executor)
    {
        if(mExpended)
        {
            throw new IllegalStateException("Attempt to re-start an expended tuner ua.in.smartjava.channel ua.in.smartjava.source.  TunerChannelSource" +
                " objects can only be used once. ");
        }

        if(mRunning.compareAndSet(false, true))
        {
            //Broadcast current frequency and ua.in.smartjava.sample rate settings so all downstream components are aware
            mDownstreamFrequencyEventProcessor.broadcastCurrentFrequency();
            mDownstreamFrequencyEventProcessor.broadcastCurrentSampleRate();

            //Schedule the decimation task to run every 9 ms (111 iterations/second), an odd periodicity relative
            //to the inbound periodicity of 20 ms, to attempt to avoid thread queue contention
            mTaskHandle = executor.scheduleAtFixedRate(mDecimationProcessor, 0, 9, TimeUnit.MILLISECONDS);

		    /* Finally, register to receive samples from the tuner */
            mTuner.addListener((Listener<ComplexBuffer>) this);
        }
        else
        {
            mLog.warn("Attempt to start() an already running tuner ua.in.smartjava.channel ua.in.smartjava.source was ignored");
        }
    }

    @Override
    public void reset()
    {
    }

    @Override
    public void stop()
    {
        if(mRunning.compareAndSet(true, false))
        {
            mTuner.releaseChannel(this);
            mDecimationProcessor.shutdown();

            if(mTaskHandle != null)
            {
                mTaskHandle.cancel(true);
                mTaskHandle = null;
            }

            mBuffer.clear();

            mExpended = true;
        }
        else
        {
            mLog.warn("Attempt to stop() an already stopped tuner ua.in.smartjava.channel ua.in.smartjava.source was ignored");
        }
    }


    @Override
    public void dispose()
    {
        if(!mRunning.get())
        {
	    	/* Tell the tuner to release/unregister our resources */
            mTuner.getTunerController().removeListener(this);
        }
    }

    /**
     * Changes the frequency correction value and broadcasts the change to the registered downstream listener.
     * @param correction current frequency correction value.
     */
    private void setFrequencyCorrection(int correction)
    {
        mChannelFrequencyCorrection = correction;

        updateMixerFrequencyOffset();

        mDownstreamFrequencyEventProcessor.broadcast(
            new FrequencyChangeEvent( Event.NOTIFICATION_CHANNEL_FREQUENCY_CORRECTION_CHANGE, mChannelFrequencyCorrection));
    }

    public Tuner getTuner()
    {
        return mTuner;
    }

    public TunerChannel getTunerChannel()
    {
        return mTunerChannel;
    }

    @Override
    public void receive(ComplexBuffer buffer)
    {
        if(mRunning.get())
        {
            mBuffer.offer(buffer);
        }
    }

    public void setFrequencyChangeListener(IFrequencyChangeProcessor processor)
    {
        mFrequencyChangeProcessor = processor;
    }

    @Override
    public void setListener(Listener<ComplexBuffer> listener)
    {
		/* Save a pointer to the listener so that if we have to change the 
		 * decimation ua.in.smartjava.filter, we can re-add the listener */
        mListener = listener;

        mDecimationFilter.setListener(listener);
    }

    @Override
    public void removeListener(Listener<ComplexBuffer> listener)
    {
        mDecimationFilter.removeListener();
    }

    /**
     * Handler for frequency change events received from the tuner and ua.in.smartjava.channel
     * frequency correction events received from the ua.in.smartjava.channel consumer/listener
     */
    @Override
    public void frequencyChanged(FrequencyChangeEvent event) throws SourceException
    {
        // Echo the event to the registered event listener
        if(mFrequencyChangeProcessor != null)
        {
            mFrequencyChangeProcessor.frequencyChanged(event);
        }

        switch(event.getEvent())
        {
            case NOTIFICATION_FREQUENCY_CHANGE:
                mTunerFrequency = event.getValue().longValue();
                updateMixerFrequencyOffset();

                //Reset frequency correction so that downstream components can recalculate the value
                setFrequencyCorrection(0);
                break;
            case NOTIFICATION_SAMPLE_RATE_CHANGE:
                int sampleRate = event.getValue().intValue();
                setSampleRate(sampleRate);
                break;
            default:
                break;
        }
    }

    /**
     * Updates the ua.in.smartjava.sample rate to the requested value and notifies any downstream components of the change
     * @param sampleRate to set
     */
    private void setSampleRate(int sampleRate)
    {
        if(mTunerSampleRate != sampleRate)
        {
            mMixer.setSampleRate(sampleRate);

            /* Get new decimation ua.in.smartjava.filter */
            mDecimationFilter = FilterFactory.getDecimationFilter(sampleRate, CHANNEL_RATE, 1,
                CHANNEL_PASS_FREQUENCY, 60, WindowType.HAMMING);

            /* re-add the original output listener */
            mDecimationFilter.setListener(mListener);

            mTunerSampleRate = sampleRate;

            mDownstreamFrequencyEventProcessor.broadcastCurrentSampleRate();
        }
    }

    /**
     * Calculates the local mixer frequency offset from the tuned frequency,
     * ua.in.smartjava.channel's requested frequency, and ua.in.smartjava.channel frequency correction.
     */
    private void updateMixerFrequencyOffset()
    {
        long offset = mTunerFrequency - mTunerChannel.getFrequency() - mChannelFrequencyCorrection;
        mMixer.setFrequency(offset);
    }

    public int getSampleRate() throws SourceException
    {
        return CHANNEL_RATE;
    }

    public long getFrequency() throws SourceException
    {
        return mTunerChannel.getFrequency();
    }

    /**
     * Implements IFrequencyChangeProvider to enable this ua.in.smartjava.source to broadcast frequency change events to downstream
     * listeners.
     *
     * @param listener to receive downstream events
     */
    @Override
    public void setFrequencyChangeListener(Listener<FrequencyChangeEvent> listener)
    {
        mDownstreamFrequencyEventProcessor.setFrequencyChangeListener(listener);
    }

    /**
     * Implements IFrequencyChangeProvider to remove the frequency change listener from receiving down-stream frequency
     * change events.
      */
    @Override
    public void removeFrequencyChangeListener()
    {
        mDownstreamFrequencyEventProcessor.removeFrequencyChangeListener();
    }

    /**
     * Implements IFrequencyChangeListener to receive frequency change events containing requests from downstream
     * listeners to change frequency values.
     * @return listener
     */
    @Override
    public Listener<FrequencyChangeEvent> getFrequencyChangeListener()
    {
        return mDownstreamFrequencyEventProcessor.getFrequencyChangeListener();
    }

    /**
     * Registers the listener to receive heartbeats from this ua.in.smartjava.source.
     * @param listener to receive heartbeats
     */
    @Override
    public void setHeartbeatListener(Listener<Heartbeat> listener)
    {
        mHeartbeatListener = listener;
    }

    /**
     * Removes the currently registered heartbeat listener
     */
    @Override
    public void removeHeartbeatListener()
    {
        mHeartbeatListener = null;
    }

    /**
     * Managers frequency change requests and notifications from/to any downstream component.  Downstream
     * components are those that receive samples from this tuner ua.in.smartjava.channel ua.in.smartjava.source.  These downstream components will be
     * notified of any frequency or ua.in.smartjava.sample rate change events and will also be able to request frequency correction
     * updates.
     */
    public class DownstreamProcessor implements IFrequencyChangeListener, IFrequencyChangeProvider,
        Listener<FrequencyChangeEvent>
    {
        //Listener to receive downstream events
        private Listener<FrequencyChangeEvent> mListener;

        /**
         * Broadcasts the frequency change event to the downstream frequency change listener
         * @param event to broadcast
         */
        public void broadcast(FrequencyChangeEvent event)
        {
            if(mListener != null)
            {
                mListener.receive(event);
            }
        }

        /**
         * Broadcasts the current frequency of this tuner ua.in.smartjava.channel ua.in.smartjava.source to the downstream listener
         */
        public void broadcastCurrentFrequency()
        {
            try
            {
                long frequency = getFrequency();
                broadcast(new FrequencyChangeEvent(Event.NOTIFICATION_FREQUENCY_CHANGE, frequency));
            }
            catch(SourceException se)
            {
                mLog.error("Error obtaining frequency from tuner to broadcast downstream");
            }
        }

        /**
         * Broadcasts the current decimated ua.in.smartjava.sample rate of this tuner ua.in.smartjava.channel ua.in.smartjava.source
         */
        public void broadcastCurrentSampleRate()
        {
            try
            {
                //Note: downstream ua.in.smartjava.sample rate is currently a fixed value -- it will change in the future
                broadcast(new FrequencyChangeEvent(Event.NOTIFICATION_SAMPLE_RATE_CHANGE, getSampleRate()));
            }
            catch(SourceException se)
            {
                mLog.error("Error obtaining ua.in.smartjava.sample rate from tuner to broadcast downstream");
            }
        }

        /**
         * Sets the downstream listener to receive frequency change events from this tuner ua.in.smartjava.channel ua.in.smartjava.source
         * @param listener to receive events
         */
        @Override
        public void setFrequencyChangeListener(Listener<FrequencyChangeEvent> listener)
        {
            mListener = listener;
        }

        /**
         * Removes the downstream listener from receiving frequency change events.
         */
        @Override
        public void removeFrequencyChangeListener()
        {
            mListener = null;
        }

        /**
         * Listener for receiving frequency change events from downstream components
         */
        @Override
        public Listener<FrequencyChangeEvent> getFrequencyChangeListener()
        {
            return this;
        }

        /**
         * Processes frequency change events from downstream components.
         * @param event to process
         */
        @Override
        public void receive(FrequencyChangeEvent event)
        {
            switch(event.getEvent())
            {
                //Frequency correction requests are the only change requests supported from downstream components
                case REQUEST_CHANNEL_FREQUENCY_CORRECTION_CHANGE:
                    setFrequencyCorrection(event.getValue().intValue());
                    break;
            }
        }
    }

    /**
     * Decimates an inbound ua.in.smartjava.buffer of I/Q samples from the ua.in.smartjava.source down to the
     * standard 48000 ua.in.smartjava.channel ua.in.smartjava.sample rate
     */
    public class DecimationProcessor implements Runnable
    {
        private boolean mProcessing = true;
        private List<ComplexBuffer> mSampleBuffers = new ArrayList<ComplexBuffer>();

        public void shutdown()
        {
            mProcessing = false;
        }

        @Override
        public void run()
        {
			/* General exception handler so that any errors won't kill the
			 * decimation thread and cause the input buffers to fill up and
			 * run the program out of memory */
            try
            {
                if(mProcessing)
                {
                    //Send a heartbeat every time this runs to allow downstream components to perform periodic
                    //state monitoring functions on this thread
                    if(mHeartbeatListener != null)
                    {
                        mHeartbeatListener.receive(HEARTBEAT);
                    }

                    mBuffer.drainTo(mSampleBuffers, 20);

                    for(Buffer buffer : mSampleBuffers)
                    {
							/* Check to see if we've been shutdown */
                        if(!mProcessing)
                        {
                            mBuffer.clear();
                            return;
                        }
                        else
                        {
                            float[] samples = buffer.getSamples();

								/* We make a copy of the ua.in.smartjava.buffer so that we don't affect
								 * anyone else that is using the same ua.in.smartjava.buffer, like other
								 * channels or the spectral display */
                            float[] translated = new float[samples.length];

								/* Perform frequency translation */
                            for(int x = 0; x < samples.length; x += 2)
                            {
                                mMixer.rotate();

                                translated[x] = Complex.multiplyInphase(
                                    samples[x], samples[x + 1], mMixer.inphase(), mMixer.quadrature());

                                translated[x + 1] = Complex.multiplyQuadrature(
                                    samples[x], samples[x + 1], mMixer.inphase(), mMixer.quadrature());
                            }

                            if(mProcessing)
                            {
                                final ComplexPrimeCICDecimate filter = mDecimationFilter;
                                filter.receive(new ComplexBuffer(translated));
                            }
                        }
                    }

                    mSampleBuffers.clear();
                }
            }
            catch(Exception e)
            {
				/* Only log the stack trace if we're still processing */
                if(mProcessing)
                {
                    mLog.error("Error encountered during decimation process", e);
                }
            }
            catch(Throwable throwable)
            {
                mLog.error("Code error encountered during decimation process - ua.in.smartjava.channel thread will probably die", throwable);
            }

			/* Check to see if we've been shutdown */
            if(!mProcessing)
            {
                mBuffer.clear();
                mSampleBuffers.clear();
            }
        }
    }
}