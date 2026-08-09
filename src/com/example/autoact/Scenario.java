package com.example.autoact;

import java.util.ArrayList;
import java.util.List;

public class Scenario {
    public String name;
    public String targetPackage;
    public long defaultTimeoutMs = 5000L;
    public long maxDurationMs = 5 * 60 * 1000L;
    // (C) When true, ScenarioRunner accumulates a StepReport per executed step
    // and ApiHandler.execScenario includes it in the response as `report`.
    public boolean withReport;
    public List<Step> steps = new ArrayList<Step>();
}
