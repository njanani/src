#ifndef __FLECS_ICE__
#define __FLECS_ICE__


module FleCS
{
	sequence<byte> ByteSeq;
	sequence<string> StringSeq;


	// Client-to-server
	interface C2S
	{
		// TODO: Add version as a parameter. A simple scalar value will do? or a vector clock?
		void Get(string bucketID, string objID, out ByteSeq content);
		void Put(string bucketID, string objID, ByteSeq content);
		void Append(string bucketID, string objID, ByteSeq content);
	};

};

#endif
