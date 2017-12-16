/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2014 Dennis Sheirer
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package ua.in.smartjava.source.mixer;

import ua.in.smartjava.channel.heartbeat.Heartbeat;
import ua.in.smartjava.dsp.gain.AutomaticGainControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.in.smartjava.sample.Listener;
import ua.in.smartjava.sample.adapter.ISampleAdapter;
import ua.in.smartjava.sample.real.RealBuffer;
import ua.in.smartjava.source.RealSource;
import ua.in.smartjava.source.SourceException;
import ua.in.smartjava.source.tuner.frequency.FrequencyChangeEvent;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public class RealMixerSource extends RealSource
{
    private final static Logger mLog = LoggerFactory.getLogger(RealMixerSource.class);

    private long mFrequency = 0;
    private int mBufferSize = 16384;

    private BufferReader mBufferReader = new BufferReader();
    private TargetDataLine mTargetDataLine;
    private AudioFormat mAudioFormat;
    private int mBytesPerFrame = 0;
    private ISampleAdapter mSampleAdapter;
    private AutomaticGainControl mAGC = new AutomaticGainControl();
    private Listener<Heartbeat> mHeartbeatListener;
    private static final Heartbeat HEARTBEAT = new Heartbeat();

    private CopyOnWriteArrayList<Listener<RealBuffer>> mSampleListeners = new CopyOnWriteArrayList<Listener<RealBuffer>>();

    /**
     * Real Mixer Source - constructs a reader on the mixer/sound card target
     * data line using the specified ua.in.smartjava.audio format (ua.in.smartjava.sample size, ua.in.smartjava.sample rate )
     * and broadcasts real buffers (float arrays) to all registered listeners.
     * Reads buffers sized to 10% of the ua.in.smartjava.sample rate specified in ua.in.smartjava.audio format.
     *
     * @param targetDataLine - mixer or sound card to be used
     * @param format - ua.in.smartjava.audio format
     * @param sampleAdapter - adapter to convert byte array data read from the
     * mixer into float array data.  Can optionally invert the ua.in.smartjava.channel data if
     * the left/right stereo channels are inverted.
     */
    public RealMixerSource(TargetDataLine targetDataLine,
                           AudioFormat format,
                           ISampleAdapter sampleAdapter)
    {
        mTargetDataLine = targetDataLine;
        mAudioFormat = format;
        mSampleAdapter = sampleAdapter;
    }

    @Override
    public void setFrequencyChangeListener(Listener<FrequencyChangeEvent> listener)
    {
        //Not implemented
    }

    @Override
    public void removeFrequencyChangeListener()
    {
        //Not implemented
    }

    @Override
    public Listener<FrequencyChangeEvent> getFrequencyChangeListener()
    {
        //Not implemented
        return null;
    }

    /**
     * Registers the listener to receive heartbeats from this ua.in.smartjava.source.
     *
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

    @Override
    public void reset()
    {
    }

    @Override
    public void start(ScheduledExecutorService executor)
    {
    }

    @Override
    public void stop()
    {
    }

    public void setListener(Listener<RealBuffer> listener)
    {
        mSampleListeners.add(listener);

		/* If this is the first listener, start the reader thread */
        if(!mBufferReader.isRunning())
        {
            Thread thread = new Thread(mBufferReader);
            thread.setDaemon(true);
            thread.setName("Sample Reader");
            thread.start();
        }
    }

    public void removeListener(Listener<RealBuffer> listener)
    {
        mSampleListeners.remove(listener);

		/* If this is the laster listener, stop the reader thread */
        if(mSampleListeners.isEmpty())
        {
            mBufferReader.stop();
        }
    }

    public void broadcast(RealBuffer samples)
    {
        Iterator<Listener<RealBuffer>> it = mSampleListeners.iterator();

        while(it.hasNext())
        {
            Listener<RealBuffer> next = it.next();
			
			/* if this is the last (or only) listener, send him the original 
			 * ua.in.smartjava.buffer, otherwise send him a copy of the ua.in.smartjava.buffer */
            if(it.hasNext())
            {
                next.receive(samples.copyOf());
            }
            else
            {
                next.receive(samples);
            }
        }
    }

    public int getSampleRate()
    {
        if(mTargetDataLine != null)
        {
            return (int)mTargetDataLine.getFormat().getSampleRate();
        }
        else
        {
            return 0;
        }
    }

    /**
     * Returns the frequency of this ua.in.smartjava.source.  Default is 0.
     */
    public long getFrequency() throws SourceException
    {
        return mFrequency;
    }

    /**
     * Specify the frequency that will be returned from this ua.in.smartjava.source.  This may
     * be useful if you are streaming an external ua.in.smartjava.audio ua.in.smartjava.source in through the
     * sound card and you want to specify a frequency for that ua.in.smartjava.source
     */
    public void setFrequency(long frequency)
    {
        mFrequency = frequency;
    }

    /**
     * Reader thread.  Performs blocking read against the mixer target data
     * line, converts the samples to an array of floats using the specified
     * adapter.  Dispatches float arrays to all registered listeners.
     */
    public class BufferReader implements Runnable
    {
        private AtomicBoolean mRunning = new AtomicBoolean();

        @Override
        public void run()
        {
            if(mRunning.compareAndSet(false, true))
            {
                //Send a heartbeat every time we run
                if(mHeartbeatListener != null)
                {
                    mHeartbeatListener.receive(HEARTBEAT);
                }

                if(mTargetDataLine == null)
                {
                    mRunning.set(false);

                    mLog.error("ComplexMixerSource - mixer target data line"
                        + " is null");
                }
                else
                {
                    mBytesPerFrame = mAudioFormat.getSampleSizeInBits() / 8;

                    if(mBytesPerFrame == AudioSystem.NOT_SPECIFIED)
                    {
                        mBytesPerFrame = 2;
                    }
    	    		
    	    		/* Set ua.in.smartjava.buffer size to 1/10 second of samples */
                    mBufferSize = (int)(mAudioFormat.getSampleRate()
                        * 0.05) * mBytesPerFrame;

            		/* We'll reuse the same ua.in.smartjava.buffer for each read */
                    byte[] buffer = new byte[mBufferSize];

                    try
                    {
                        mTargetDataLine.open(mAudioFormat);

                        mTargetDataLine.start();
                    }
                    catch(LineUnavailableException e)
                    {
                        mLog.error("ComplexMixerSource - mixer target data line"
                            + "not available to read data from", e);

                        mRunning.set(false);
                    }

                    while(mRunning.get())
                    {
                        try
                        {
            				/* Blocking read - waits until the ua.in.smartjava.buffer fills */
                            mTargetDataLine.read(buffer, 0, buffer.length);

	                        /* Convert samples to float array */
                            float[] samples =
                                mSampleAdapter.convert(buffer);
	            			
	            			/* Dispatch samples to registered listeners */
                            broadcast(new RealBuffer(samples));
                        }
                        catch(Exception e)
                        {
                            mLog.error("ComplexMixerSource - error while reading"
                                + "from the mixer target data line", e);

                            mRunning.set(false);
                        }
                    }
                }
        		
        		/* Close the data line if it is still open */
                if(mTargetDataLine != null && mTargetDataLine.isOpen())
                {
                    mTargetDataLine.close();
                }
            }
        }

        /**
         * Stops the reader thread
         */
        public void stop()
        {
            if(mRunning.compareAndSet(true, false))
            {
                mTargetDataLine.stop();
                mTargetDataLine.close();
            }
        }

        /**
         * Indicates if the reader thread is running
         */
        public boolean isRunning()
        {
            return mRunning.get();
        }
    }

    @Override
    public void dispose()
    {
        if(mBufferReader != null)
        {
            mBufferReader.stop();
            mSampleListeners.clear();
        }
    }
}