package org.apache.cassandra.flecs;
import org.apache.cassandra.ice.*;


public class FleCSClient {

  Ice.Communicator ic;
  int status;
  C2SPrx c2s_prx;
  public FleCSClient()
  {
   ic = null;
        status = 0;
  }
  public int init()
  {
   try{
	  String [] args = {""};
      ic = Ice.Util.initialize(args);
      Ice.ObjectPrx base = ic.stringToProxy("c2s:tcp -p 10000");
      c2s_prx = C2SPrxHelper.checkedCast(base);
      if (c2s_prx == null)
           throw new Error("Invalid proxy");
   }
   catch(Exception e)
   {
      System.err.println(e.getMessage());
           status = 1;
   }
   return status;
  }

  public byte[] Get(String bucket_id, String object_id)
  {
   try
   {
      ByteSeqHolder content = new ByteSeqHolder();
      c2s_prx.Get(bucket_id,object_id,content);
      byte [] value = content.value;
      return value;
 //     String output = new String(value);
 //     System.out.println(output);
   }

   catch (Ice.LocalException e) {
            e.printStackTrace();
            return null;
        }
   catch(Exception e)
   {
      System.err.println(e.getMessage());
               return null;
   }
   
  }

  public int Put(String bucket_id, String object_id, byte[] content)
  {
   try
   {
      c2s_prx.Put(bucket_id, object_id, content);
   }

   catch (Ice.LocalException e) {
            e.printStackTrace();
            status = 1;
        }
   catch(Exception e)
   {
      e.printStackTrace();
         status = 1;
   }
   return status;
  }
  public int Append(String bucket_id, String object_id, byte[] content)
  {
   try
        {
                c2s_prx.Append(bucket_id, object_id, content);
        }

        catch (Ice.LocalException e) {
            e.printStackTrace();
            status = 1;
        }
        catch(Exception e)
        {
                e.printStackTrace();
                status = 1;
        }
   return status;
  }
  public int cleanup()
  {

        if (ic != null) {
            // Clean up
            //
            try {
                ic.destroy();
            } catch (Exception e) {
                System.err.println(e.getMessage());
                status = 1;
            }
        }
   return status;
  }


}
                            