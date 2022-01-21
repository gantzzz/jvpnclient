package online.justvpn;

enum Sig {BEST, GOOD, MID, LOW, UNKNOWN};
public class ServerListItemDataModel
{
    private int mId;
    private String mIp;
    private String mCountry;
    private Sig mSignal;

    public ServerListItemDataModel(int id, String ip, String country, Sig sig)
    {
        mId = id;
        mIp = ip;
        mCountry = country;
        mSignal = sig;
    }

    public String get_ip()
    {
        return mIp;
    }
    public String get_country()
    {
        return mCountry;
    }
    public int get_id() { return mId; }
    public Sig get_signal() { return mSignal; }

    public void set_signal(Sig sig) { mSignal = sig; }
}
