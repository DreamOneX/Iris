package com.volmit.iris.util.decree.handlers;

import com.volmit.iris.Iris;
import com.volmit.iris.core.project.loader.IrisData;
import com.volmit.iris.engine.object.noise.IrisGenerator;
import com.volmit.iris.util.collection.KList;
import com.volmit.iris.util.collection.KMap;
import com.volmit.iris.util.decree.DecreeParameterHandler;
import com.volmit.iris.util.decree.exceptions.DecreeParsingException;
import com.volmit.iris.util.decree.exceptions.DecreeWhichException;

import java.io.File;

public class GeneratorHandler implements DecreeParameterHandler<IrisGenerator> {
    @Override
    public KList<IrisGenerator> getPossibilities() {
        KMap<String, IrisGenerator> p = new KMap<>();

        //noinspection ConstantConditions
        for(File i : Iris.instance.getDataFolder("packs").listFiles())
        {
            if(i.isDirectory()) {
                IrisData data = new IrisData(i, true);
                for (IrisGenerator j : data.getGeneratorLoader().loadAll(data.getGeneratorLoader().getPossibleKeys()))
                {
                    p.putIfAbsent(j.getLoadKey(), j);
                }

                data.close();
            }
        }

        return p.v();
    }

    @Override
    public String toString(IrisGenerator gen) {
        return gen.getLoadKey();
    }

    @Override
    public IrisGenerator parse(String in) throws DecreeParsingException, DecreeWhichException {
        try
        {
            KList<IrisGenerator> options = getPossibilities(in);

            if(options.isEmpty())
            {
                throw new DecreeParsingException("Unable to find Generator \"" + in + "\"");
            }

            else if(options.size() > 1)
            {
                throw new DecreeWhichException();
            }

            return options.get(0);
        }
        catch(DecreeParsingException e){
            throw e;
        }
        catch(Throwable e)
        {
            throw new DecreeParsingException("Unable to find Generator \"" + in + "\" because of an uncaught exception: " + e);
        }
    }

    @Override
    public boolean supports(Class<?> type) {
        return type.equals(IrisGenerator.class);
    }

    @Override
    public String getRandomDefault()
    {
        return "generator";
    }
}