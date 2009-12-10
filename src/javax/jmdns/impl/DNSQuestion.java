//Copyright 2003-2005 Arthur van Hoff, Rick Blair
//Licensed under Apache License version 2.0
//Original license LGPL

package javax.jmdns.impl;

/**
 * A DNS question.
 *
 * @version %I%, %G%
 * @author Arthur van Hoff
 */
public final class DNSQuestion extends DNSEntry
{
    // private static Logger logger = Logger.getLogger(DNSQuestion.class.getName());

    /**
     * Create a question.
     *
     * @param name
     * @param type
     * @param recordClass
     * @param unique
     */
    public DNSQuestion(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique)
    {
        super(name, type, recordClass, unique);
    }

    /**
     * Check if this question is answered by a given DNS record.
     */
    boolean answeredBy(DNSEntry rec)
    {
        return (this.getRecordClass() == rec.getRecordClass())
                && ((this.getRecordType() == rec.getRecordType()) || DNSRecordType.TYPE_ANY
                        .equals(this.getRecordType())) && this.getName().equals(rec.getName());
    }

    @Override
    boolean isExpired(long now)
    {
        return false;
    }

}
