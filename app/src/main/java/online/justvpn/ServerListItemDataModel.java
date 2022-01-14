package online.justvpn;

public class ServerListItemDataModel
{
    private int mId;
    private String mIp;
    private String mCountry;

    public ServerListItemDataModel(int id, String ip, String country)
    {
        mId = id;
        mIp = ip;
        mCountry = country;
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
}
