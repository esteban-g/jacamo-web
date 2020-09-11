package jacamo.web.mediation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

import jacamo.rest.mediation.TranslAg;
import jacamo.web.implementation.WebImpl;
import jason.JasonException;
import jason.asSemantics.Agent;
import jason.asSemantics.GoalListenerForMetaEvents;
import jason.asSemantics.Unifier;
import jason.asSyntax.ASSyntax;
import jason.asSyntax.ListTerm;
import jason.asSyntax.ListTermImpl;
import jason.asSyntax.Literal;
import jason.asSyntax.Term;
import jason.asSyntax.VarTerm;
import jason.asSyntax.parser.ParseException;
import jason.asSyntax.parser.TokenMgrError;
import jason.runtime.RuntimeServicesFactory;
import jason.runtime.SourcePath;

public class TranslAgWeb extends TranslAg {

    /**
     * Converts the transport format to a valid URI
     * eg.: 'src%2Ftest%2Fsearch' returns 'file:src/test/search.asl'
	 * @param agURI
	 * @return a formatted URI
	 */
	public String getFormattedURI(String agURI) {
		String uri = agURI.replaceAll("%2F", "/");
		
		uri = uri.replaceAll("\\\\", "/");

        if (!uri.endsWith(".asl")) 
        	uri = uri + ".asl";

		SourcePath aslSourcePath = new SourcePath();
        uri = aslSourcePath.fixPath(uri);
        
        return uri;
	}

	/**
     * Return the agent name from a file path
     * e.g: from 'walking/goto' returns 'goto'
	 * from './src/test/search/astar.asl' returns 'astar'
	 * @param agURI
	 * @return the name of the agent
	 */
	public String getAgentName(String agURI) {
		String agName = agURI;

        if (agName.endsWith(".asl")) 
        	agName = agName.substring(0, agName.lastIndexOf(".asl"));
        
        return agName.substring(agName.lastIndexOf("/") + 1);
	}
	
	/**
	 * Return if the string is a URI (starts with http://
	 * @param agURI
	 * @return boolean
	 */
	public boolean isRemoteURI(String agURI) {
        return agURI.startsWith("http");
	}
	
	/**
     * Create agent and corresponding asl file with the agName if possible, or agName_1, agName_2,...
     * 
     * @param agURI
     * @return
     * @throws Exception
     * @throws JasonException
     */
    @Override
    public String createAgent(String agURI) throws Exception, JasonException {
        String formattedURI = getFormattedURI(agURI);
        String agName = getAgentName(agURI);

        String givenName = RuntimeServicesFactory.get().createAgent(agName, null, null, null, null, null, null);
        RuntimeServicesFactory.get().startAgent(givenName);
        // set some source for the agent
        Agent ag = getAgent(givenName);

        if (isRemoteURI(agURI)) {
        	try {
                URI uri = new URI(formattedURI);
                ag.load(uri.toURL().openStream(), formattedURI);
                ag.setASLSrc(formattedURI);
        	} catch (URISyntaxException e) {
        		e.printStackTrace();
        	}
        } else {
            try {
                File f = new File(formattedURI);
                f.getParentFile().mkdirs();
                if (!f.exists()) {
                    f.createNewFile();
                    FileOutputStream outputFile = new FileOutputStream(f, false);
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append("//Agent created automatically\n\n");
                    stringBuilder.append("!start.\n\n");
                    stringBuilder.append("+!start <- .print(\"Hi\").\n\n");
                    stringBuilder.append("{ include(\"$jacamoJar/templates/common-cartago.asl\") }\n");
                    stringBuilder.append("{ include(\"$jacamoJar/templates/common-moise.asl\") }\n");
                    stringBuilder.append(
                            "// uncomment the include below to have an agent compliant with its organisation\n");
                    stringBuilder.append("//{ include(\"$moiseJar/asl/org-obedient.asl\") }");
                    byte[] bytes = stringBuilder.toString().getBytes();
                    outputFile.write(bytes);
                    outputFile.close();
                }
                ag.load(new FileInputStream(formattedURI), formattedURI);
                ag.setASLSrc(formattedURI);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        // ag.setASLSrc("no-inicial.asl");
        createAgLog(givenName, ag);
        return givenName;
    }
    
    /**
     * get deductions from a given rule (by a predicateIndicator)
     * 
     * @param agName
     * @param predicateIndicator (e.g. parent/2)
     * @return
     */
    public Object getDeductions(String agName, String predicateIndicator) {
        Agent ag = getAgent(agName);
        for (Literal l : ag.getBB()) {
            if (predicateIndicator.equals(l.getFunctor() + "/" + l.getArity())) {
                List<String> predicates = new ArrayList<>();
                try {
                    String terms = "";
                    if (l.getArity() > 0) {
                        for (int i = 0; i < l.getArity(); i++) {
                            if (i == 0)
                                terms = "(";
                            terms += l.getTerm(i).toString();
                            if (i < l.getArity() - 1)
                                terms += ", ";
                            else
                                terms += ")";
                        }
                    }
                    Unifier u;
                    u = execCmd(ag, ASSyntax.parsePlanBody(".findall("+l.getFunctor() + terms+","+l.getFunctor() + terms+",L)"));
                    String deductions = "";
                    for (VarTerm v : u) 
                        deductions += u.get(v).toString();
                    ListTerm lt = ListTermImpl.parseList(deductions);
                    for (Term li : lt) {
                        predicates.add(((Literal)li).toString());
                    }
                } catch (ParseException e) {
                    e.printStackTrace();
                } catch (TokenMgrError e) {
                    e.printStackTrace();
                }
                return predicates;
            }
        }
        return null;
    }

    /**
     * Agent agName loads an asl file content
     * 
     * @param agName
     * @param aslFileName
     * @param uploadedInputStream
     * @throws IOException
     * @throws ParseException
     * @throws JasonException
     */
	public void loadASLFileContent(String agName, String aslFileName, InputStream uploadedInputStream)
			throws IOException, ParseException, JasonException {
		Agent ag = getAgent(agName);
		if (ag != null) {
			System.out.println("agName: " + agName);
			System.out.println("restAPI://" + aslFileName);
			System.out.println("uis: " + uploadedInputStream);

			// Save new code
			StringBuilder stringBuilder = new StringBuilder();
			String line = null;

			FileOutputStream outputFile = new FileOutputStream(getFormattedURI(aslFileName), false);
			BufferedReader out = new BufferedReader(new InputStreamReader(uploadedInputStream));

			while ((line = out.readLine()) != null) {
				stringBuilder.append(line + "\n");
			}

			byte[] bytes = stringBuilder.toString().getBytes();
			outputFile.write(bytes);
			outputFile.close();

			// Reload agent with this new code
			ag.getPL().clear();

			ag.parseAS(new FileInputStream(getFormattedURI(aslFileName)), getFormattedURI(aslFileName));
			if (ag.getPL().hasMetaEventPlans())
				ag.getTS().addGoalListener(new GoalListenerForMetaEvents(ag.getTS()));

			ag.loadKqmlPlans();
		}

	}
	
	/**
	 * Return the content of an asl file
	 * 
	 * @param aslURI
	 * @return
	 * @throws IOException
	 */
	public String getASLFileContent(String aslURI) throws IOException {
		StringBuilder so = new StringBuilder();
		BufferedReader in = getFileBuffer(aslURI);
		String line = in.readLine();
		while (line != null) {
			so.append(line + "\n");
			line = in.readLine();
		}
		return so.toString();
	}
	
	/**
	 * Return a buffer to a file from the given URI. If not exists returns null.
	 * 
	 * 
	 * @param aslURI
	 * @return a BufferedReader or null
	 * @throws IOException
	 */
	public BufferedReader getFileBuffer(String aslURI) throws IOException {

        String formattedURI = getFormattedURI(aslURI);
        
		BufferedReader in = null;
		File f = new File(formattedURI);
		if (f.exists()) {
			in = new BufferedReader(new FileReader(f));
		} else {
            try {
                URI uri = new URI(formattedURI);
    			in = new BufferedReader(
    					new InputStreamReader(uri.toURL().openStream()));
            } catch (MalformedURLException | URISyntaxException e) {
    			in = new BufferedReader(
    					new InputStreamReader(WebImpl.class.getResource(formattedURI).openStream()));
			}
		}
		return in;
	}

}

