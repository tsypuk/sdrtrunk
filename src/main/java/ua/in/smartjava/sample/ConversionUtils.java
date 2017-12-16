package ua.in.smartjava.sample;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import ua.in.smartjava.sample.real.RealBuffer;

/**
 * Utilities for converting to/from signed 16-bit ua.in.smartjava.sample byte arrays and float buffers
 */
public class ConversionUtils
{
    public static float[] convertFromSigned16BitSamples( byte[] bytes )
    {
    	return convertFromSigned16BitSamples( ByteBuffer.wrap( bytes ) );
    }

    /**
     * Converts the byte ua.in.smartjava.buffer containing 16-bit samples into a float array
     */
    public static float[] convertFromSigned16BitSamples( ByteBuffer buffer )
    {
    	ShortBuffer byteBuffer = 
    			buffer.order( ByteOrder.LITTLE_ENDIAN ).asShortBuffer();
    	
    	float[] samples = new float[ buffer.limit() / 2 ];
    	
    	for( int x = 0; x < samples.length; x++ )
    	{
    		samples[ x ] = (float)byteBuffer.get() / 32767.0f;
    	}

    	return samples;
    }

    /**
     * Converts the float samples into a little-endian 16-bit ua.in.smartjava.sample byte ua.in.smartjava.buffer.
     * @param samples - float array of ua.in.smartjava.sample data
     * @return - little-endian 16-bit ua.in.smartjava.sample byte ua.in.smartjava.buffer
     */
    public static ByteBuffer convertToSigned16BitSamples( float[] samples )
    {
		ByteBuffer converted = ByteBuffer.allocate( samples.length * 2 );
		converted.order( ByteOrder.LITTLE_ENDIAN );

		for( float sample: samples )
		{
			converted.putShort( (short)( sample * Short.MAX_VALUE ) );
		}
		
		return converted;
    }
    
	/**
	 * Converts the float samples in a complex ua.in.smartjava.buffer to a little endian 16-bit
	 * ua.in.smartjava.buffer
	 */
	public static ByteBuffer convertToSigned16BitSamples( Buffer buffer )
	{
		return convertToSigned16BitSamples( buffer.getSamples() );
	}

	/**
	 * Converts the float samples in a complex ua.in.smartjava.buffer to a little endian 16-bit
	 * ua.in.smartjava.buffer
	 */
	public static ByteBuffer convertToSigned16BitSamples( RealBuffer buffer )
	{
		return convertToSigned16BitSamples( buffer.getSamples() );
	}
}