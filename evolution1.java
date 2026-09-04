package evolution;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Phaser;

public final class evolution1 extends JFrame {
    private static final int WORLD_WIDTH = 128;
    private static final int WORLD_HEIGHT = 128;
    private static final int GENERATION_LENGTH = 250;
    private static final int[] GAME_TICK_RATES = {
        30, 90, 200, 500, 2000
    };
    private static final int MAX_TICKS_PER_BATCH = 8192;
    private final JLabel populationValue = new JLabel("0");
    private final JLabel generationValue = new JLabel("0 / 0");
    private final JLabel survivalValue = new JLabel("--");
    private final JSpinner genomeLength =
        new JSpinner(new EvenSpinnerNumberModel(16, 2, 10000, 2));
    private final JSpinner innerNeurons =
        new JSpinner(new SpinnerNumberModel(4, 0, 1000, 1));
    private final JSpinner mutationChance =
        new JSpinner(new SpinnerNumberModel(0.1, 0.0, 0.5, 0.1));
    private final JCheckBox killEnabled = new JCheckBox("Enabled", false);
    private final JCheckBox showReproductiveAreaEnabled =
        new JCheckBox("Enabled", false);
    private final JCheckBox gridEnabled = new JCheckBox("Enabled", false);
    private final JCheckBox radioactiveEnabled = new JCheckBox("Enabled", false);
    private final JTextArea areaCode = new JTextArea(7, 22);
    private final WorldPanel worldPanel = new WorldPanel();
    private final JButton startButton = new JButton("Start");
    private final JButton[] tickRateButtons =
        new JButton[GAME_TICK_RATES.length];
    private final JButton add100Button = new JButton("Add 100");
    private final JButton add1000Button = new JButton("Add 1000");
    private final JButton trainingButton = new JButton("Run training");
    private final Timer renderTimer;
    private final ScheduledExecutorService simulationExecutor =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "evolution-simulation");
            thread.setDaemon(true);
            return thread;
        });

    private volatile int generation;
    private volatile int step;
    private volatile int generationZeroPopulation;
    private volatile int requestedTicksPerSecond = GAME_TICK_RATES[0];
    private volatile boolean simulationRunning;
    private volatile boolean trainingRunning;
    private volatile int nextTrainingDisplayGeneration;
    private static final int TRAINING_UPDATE_INTERVAL = 10;
    private volatile double activeMutationChance = 0.001;
    private volatile boolean killingAllowed;
    private volatile boolean radioactivityAllowed;
    private volatile String previousSurvivalText = "--";
    private volatile boolean extinctionNoticePending;
    private volatile long lastSimulationNanos = System.nanoTime();
    private volatile double pendingTicks;

    public evolution1() {
        super("128 x 128 Evolution World");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));
        setSize(1200, 720);
        setLocationRelativeTo(null);

        renderTimer = new Timer(33, e -> refreshDisplay());
        renderTimer.setCoalesce(true);
        renderTimer.start();

        simulationExecutor.scheduleWithFixedDelay(
            this::runSimulationBatch,
            0,
            1,
            TimeUnit.MILLISECONDS
        );

        JSplitPane splitPane = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            worldPanel,
            createParametersPanel()
        );
        splitPane.setResizeWeight(0.75);
        splitPane.setDividerLocation(0.75);
        splitPane.setContinuousLayout(true);
        splitPane.setDividerSize(6);
        setContentPane(splitPane);
        updateStatus();
    }

    private JPanel createParametersPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(new Color(30, 33, 39));
        outer.setBorder(new EmptyBorder(14, 16, 14, 16));

        JLabel title = new JLabel("Simulation Parameters");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setBorder(new EmptyBorder(0, 0, 12, 0));
        outer.add(title, BorderLayout.NORTH);

        JPanel parameters = new JPanel(new GridBagLayout());
        parameters.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(6, 0, 6, 8);

        addParameter(parameters, c, "World size", new JLabel("128 x 128"));
        addParameter(parameters, c, "Population", populationValue);
        addParameter(parameters, c, "Step / Generation", generationValue);
        addParameter(parameters, c, "Previous survival", survivalValue);
        addParameter(parameters, c, "Genome length (genes)", genomeLength);
        addParameter(parameters, c, "Inner neurons", innerNeurons);
        JSpinner.NumberEditor mutationEditor =
            new JSpinner.NumberEditor(mutationChance, "0.0'%'");
        mutationChance.setEditor(mutationEditor);
        addParameter(parameters, c, "Mutation chance", mutationChance);
        addParameter(parameters, c, "Kill", killEnabled);
        addParameter(parameters, c, "Show reproductive area", showReproductiveAreaEnabled);
        addParameter(parameters, c, "Grid", gridEnabled);

        showReproductiveAreaEnabled.addActionListener(
            e -> worldPanel.setShowReproductiveArea(
                showReproductiveAreaEnabled.isSelected()
            )
        );
        mutationChance.addChangeListener(
            e -> activeMutationChance =
                ((Number) mutationChance.getValue()).doubleValue() / 100.0
        );
        killEnabled.addActionListener(
            e -> killingAllowed = killEnabled.isSelected()
        );
        gridEnabled.addActionListener(
            e -> worldPanel.setGridEnabled(gridEnabled.isSelected())
        );

        addToolControls(parameters, c, "Reproduction area", WorldPanel.Tool.REPRODUCTION);
        addToolControls(parameters, c, "Obstacles", WorldPanel.Tool.OBSTACLE);
        addWideButton(parameters, c, "Reset reproductive areas", e -> worldPanel.resetReproductiveAreas());
        addWideButton(parameters, c, "Reset obstacles", e -> worldPanel.resetObstacles());
        add100Button.addActionListener(e -> addCreatures(100));
        add1000Button.addActionListener(e -> addCreatures(1000));
        addTwoButtons(parameters, c, add100Button, add1000Button);

        addTickRateButtons(parameters, c);

        startButton.addActionListener(e -> toggleSimulation());
        addExistingWideButton(parameters, c, startButton);

        trainingButton.addActionListener(e -> toggleTraining());
        addExistingWideButton(parameters, c, trainingButton);
        addWideButton(
            parameters,
            c,
            "Restart generation",
            e -> restartGeneration()
        );
        addWideButton(parameters, c, "Restart", e -> restartSimulation());
        addWideButton(
            parameters,
            c,
            "Export average creature genes",
            e -> exportAverageCreatureGenes()
        );
        addParameter(parameters, c, "Radioactive", radioactiveEnabled);
        radioactiveEnabled.addActionListener(e -> {
            radioactivityAllowed = radioactiveEnabled.isSelected();
            worldPanel.setRadioactiveState(radioactivityAllowed, step);
        });

        c.gridx = 0;
        c.gridwidth = 2;
        c.weighty = 1.0;
        c.fill = GridBagConstraints.BOTH;
        parameters.add(Box.createGlue(), c);
        c.gridy++;

        // Keep the map code beneath every other control, at the bottom of the
        // parameter panel.
        c.weighty = 0.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        addAreaCodeControls(parameters, c);

        JScrollPane parameterScroll = new JScrollPane(parameters);
        parameterScroll.setBorder(null);
        parameterScroll.setOpaque(false);
        parameterScroll.getViewport().setOpaque(false);
        parameterScroll.getVerticalScrollBar().setUnitIncrement(16);
        outer.add(parameterScroll, BorderLayout.CENTER);
        worldPanel.setAreaChangedListener(this::refreshAreaCode);
        refreshAreaCode();
        return outer;
    }

    private void addAreaCodeControls(JPanel panel, GridBagConstraints c) {
        JLabel label = new JLabel("Compact map code:");
        label.setForeground(new Color(190, 195, 205));
        label.setFont(label.getFont().deriveFont(14f));

        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1.0;
        panel.add(label, c);
        c.gridy++;

        areaCode.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        areaCode.setLineWrap(true);
        areaCode.setWrapStyleWord(false);
        areaCode.setToolTipText(
            "Copy and paste this hexadecimal code to save or restore the map areas."
        );
        JScrollPane codeScroll = new JScrollPane(areaCode);
        codeScroll.setPreferredSize(new Dimension(260, 125));
        c.fill = GridBagConstraints.BOTH;
        panel.add(codeScroll, c);
        c.gridy++;
        c.fill = GridBagConstraints.HORIZONTAL;

        JButton applyCode = new JButton("Apply area code");
        applyCode.addActionListener(e -> applyAreaCode());
        panel.add(applyCode, c);
        c.gridy++;
        c.gridwidth = 1;
    }

    private void refreshAreaCode() {
        areaCode.setText(worldPanel.createAreaCode());
        areaCode.setCaretPosition(0);
    }

    private void applyAreaCode() {
        try {
            worldPanel.applyAreaCode(areaCode.getText());
            refreshAreaCode();
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(
                this,
                exception.getMessage(),
                "Invalid area code",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void addParameter(JPanel panel, GridBagConstraints c, String name, JComponent value) {
        JLabel label = new JLabel(name + ":");
        label.setForeground(new Color(190, 195, 205));
        label.setFont(label.getFont().deriveFont(14f));
        if (value instanceof JLabel) {
            value.setForeground(Color.WHITE);
            value.setFont(value.getFont().deriveFont(Font.BOLD, 14f));
        }
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0.55;
        panel.add(label, c);
        c.gridx = 1;
        c.weightx = 0.45;
        panel.add(value, c);
        c.gridy++;
    }

    private void addToolControls(
        JPanel panel,
        GridBagConstraints c,
        String text,
        WorldPanel.Tool tool
    ) {
        JButton toolButton = new JButton(text);
        JButton shapeButton = new JButton("Rectangle");
        toolButton.addActionListener(e -> worldPanel.selectTool(tool));
        shapeButton.addActionListener(e -> {
            boolean circle = worldPanel.toggleShape(tool);
            shapeButton.setText(circle ? "Circle" : "Rectangle");
        });
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0.65;
        panel.add(toolButton, c);
        c.gridx = 1;
        c.weightx = 0.35;
        panel.add(shapeButton, c);
        c.gridy++;
    }

    private void addWideButton(
        JPanel panel,
        GridBagConstraints c,
        String text,
        ActionListener action
    ) {
        JButton button = new JButton(text);
        button.addActionListener(action);
        addExistingWideButton(panel, c, button);
    }

    private void addExistingWideButton(
        JPanel panel,
        GridBagConstraints c,
        JButton button
    ) {
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1.0;
        panel.add(button, c);
        c.gridy++;
        c.gridwidth = 1;
    }

    private void addTwoButtons(
        JPanel panel,
        GridBagConstraints c,
        JButton leftButton,
        JButton rightButton
    ) {
        c.gridwidth = 1;
        c.weightx = 0.5;
        c.gridx = 0;
        panel.add(leftButton, c);
        c.gridx = 1;
        panel.add(rightButton, c);
        c.gridy++;
    }

    private void addTickRateButtons(
        JPanel panel,
        GridBagConstraints c
    ) {
        JPanel speedPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        speedPanel.setOpaque(false);

        JLabel label = new JLabel("Game ticks/s:");
        label.setForeground(new Color(190, 195, 205));
        label.setFont(label.getFont().deriveFont(14f));
        speedPanel.add(label);

        for (int i = 0; i < GAME_TICK_RATES.length; i++) {
            int ticksPerSecond = GAME_TICK_RATES[i];
            JButton button = new JButton(Integer.toString(ticksPerSecond));
            button.setMargin(new Insets(3, 7, 3, 7));
            button.addActionListener(e -> setTickRate(ticksPerSecond));
            tickRateButtons[i] = button;
            speedPanel.add(button);
        }

        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1.0;
        panel.add(speedPanel, c);
        c.gridy++;
        c.gridwidth = 1;

        updateTickRateButtons();
    }

    private void addCreatures(int amount) {
        if (generation != 0) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }

        worldPanel.addCreatures(
            amount,
            getGenomeLength(),
            getInnerNeurons()
        );
        generationZeroPopulation = Math.max(
            generationZeroPopulation,
            worldPanel.getPopulation()
        );
        updateStatus();
    }

    private void toggleSimulation() {
        if (trainingRunning) {
            stopTraining();
        }
        if (worldPanel.getPopulation() == 0) {
            addCreatures(1000);
        }
        if (worldPanel.getPopulation() == 0) {
            return;
        }
        if (simulationRunning) {
            simulationRunning = false;
            startButton.setText("Start");
        } else {
            pendingTicks = 0.0;
            lastSimulationNanos = System.nanoTime();
            simulationRunning = true;
            startButton.setText("Pause");
        }
    }

    private void toggleTraining() {
        if (trainingRunning) {
            stopTraining();
            return;
        }

        if (worldPanel.getPopulation() == 0) {
            addCreatures(1000);
        }
        if (worldPanel.getPopulation() == 0) return;

        simulationRunning = false;
        startButton.setText("Start");

        nextTrainingDisplayGeneration = generation + TRAINING_UPDATE_INTERVAL;
        trainingRunning = true;
        trainingButton.setText("Pause training");
        worldPanel.captureRenderSnapshot(false, generation);
        worldPanel.repaint();
    }

    private void stopTraining() {
        trainingRunning = false;
        trainingButton.setText("Run training");
        updateStatus();
        worldPanel.captureRenderSnapshot(true, generation);
        worldPanel.repaint();
    }

    private void setTickRate(int ticksPerSecond) {
        requestedTicksPerSecond = ticksPerSecond;
        pendingTicks = 0.0;
        lastSimulationNanos = System.nanoTime();
        renderTimer.setDelay(displayDelayFor(ticksPerSecond));
        renderTimer.setInitialDelay(displayDelayFor(ticksPerSecond));
        updateTickRateButtons();
    }

    private void updateTickRateButtons() {
        for (int i = 0; i < tickRateButtons.length; i++) {
            JButton button = tickRateButtons[i];
            if (button != null) {
                button.setEnabled(
                    GAME_TICK_RATES[i] != requestedTicksPerSecond
                );
            }
        }
    }

    private static int displayDelayFor(int ticksPerSecond) {
        if (ticksPerSecond >= 2000) return 67;
        return 33;
    }

    private void advanceOneSimulationTick() {
        worldPanel.advanceCreatures(
            killingAllowed,
            radioactivityAllowed,
            step
        );
        step++;

        if (step >= GENERATION_LENGTH) {
            int survivors = worldPanel.beginNextGeneration(
                activeMutationChance,
                generationZeroPopulation
            );
            double percentage = generationZeroPopulation == 0
                ? 0.0
                : survivors * 100.0 / generationZeroPopulation;
            previousSurvivalText = String.format("%.1f%% (%d)", percentage, survivors);
            generation++;
            step = 0;

            if (worldPanel.getPopulation() == 0) {
                simulationRunning = false;
                trainingRunning = false;
                extinctionNoticePending = true;
            }
        }
    }

    private void runSimulationBatch() {
        long now = System.nanoTime();
        if (trainingRunning) {
            runTrainingBatch();
            lastSimulationNanos = System.nanoTime();
            pendingTicks = 0.0;
            return;
        }
        if (!simulationRunning) {
            lastSimulationNanos = now;
            pendingTicks = 0.0;
            return;
        }

        double elapsedSeconds = Math.min(
            0.25,
            (now - lastSimulationNanos) / 1_000_000_000.0
        );
        lastSimulationNanos = now;
        pendingTicks += elapsedSeconds * requestedTicksPerSecond;

        int ticksToRun = Math.min(MAX_TICKS_PER_BATCH, (int) pendingTicks);
        if (ticksToRun == 0) return;
        pendingTicks -= ticksToRun;

        for (int i = 0; i < ticksToRun && simulationRunning; i++) {
            advanceOneSimulationTick();
        }
    }

    private void runTrainingBatch() {
        long deadline = System.nanoTime() + 200_000_000L;
        int ticksRun = 0;

        while (trainingRunning
            && ticksRun < MAX_TICKS_PER_BATCH
            && System.nanoTime() < deadline) {
            advanceOneSimulationTick();
            ticksRun++;
        }
    }

    private void refreshDisplay() {
        if (trainingRunning) {
            if (generation >= nextTrainingDisplayGeneration) {
                updateStatus();
                worldPanel.captureRenderSnapshot(false, generation);
                worldPanel.repaint();
                while (nextTrainingDisplayGeneration <= generation) {
                    nextTrainingDisplayGeneration += TRAINING_UPDATE_INTERVAL;
                }
            }
        } else {
            updateStatus();
            worldPanel.captureRenderSnapshot(true, generation);
            worldPanel.repaint();
        }

        if (extinctionNoticePending) {
            extinctionNoticePending = false;
            startButton.setText("Start");
            trainingButton.setText("Run training");
            previousSurvivalText = previousSurvivalText + " - extinct";
        }
    }

    private void restartSimulation() {
        simulationRunning = false;
        trainingRunning = false;
        startButton.setText("Start");
        trainingButton.setText("Run training");
        generation = 0;
        step = 0;
        generationZeroPopulation = 0;
        previousSurvivalText = "--";
        worldPanel.clearCreatures();
        updateStatus();
    }

    private void restartGeneration() {
        simulationRunning = false;
        trainingRunning = false;
        startButton.setText("Start");
        trainingButton.setText("Run training");

        if (worldPanel.getPopulation() == 0) {
            Toolkit.getDefaultToolkit().beep();
            return;
        }

        int populationTarget = Math.max(
            generationZeroPopulation,
            worldPanel.getPopulation()
        );

        worldPanel.restartCurrentGeneration(
            activeMutationChance,
            populationTarget
        );

        step = 0;
        pendingTicks = 0.0;
        lastSimulationNanos = System.nanoTime();
        updateStatus();
        worldPanel.captureRenderSnapshot(true, generation);
        worldPanel.repaint();
    }

    private void exportAverageCreatureGenes() {
        int[] averageGenome = worldPanel.createAverageGenome();
        if (averageGenome == null || averageGenome.length == 0) {
            Toolkit.getDefaultToolkit().beep();
            JOptionPane.showMessageDialog(
                this,
                "Add creatures before exporting an average genome.",
                "Nothing to export",
                JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        int populationAtExport = worldPanel.getPopulation();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export average creature genes");
        chooser.setSelectedFile(
            new File("average-creature-generation-" + generation + ".txt")
        );

        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File outputFile = chooser.getSelectedFile();
        if (!outputFile.getName().contains(".")) {
            outputFile = new File(
                outputFile.getParentFile(),
                outputFile.getName() + ".txt"
            );
        }

        if (outputFile.exists()) {
            int choice = JOptionPane.showConfirmDialog(
                this,
                "That file already exists. Replace it?",
                "Replace file",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(
            outputFile.toPath(),
            StandardCharsets.UTF_8
        )) {
            writer.write("# Average creature consensus genome");
            writer.newLine();
            writer.write("# Generation: " + generation);
            writer.newLine();
            writer.write("# Population: " + populationAtExport);
            writer.newLine();
            writer.write("# Genome length: " + averageGenome.length);
            writer.newLine();
            writer.write("# One 32-bit gene per line as 8 hexadecimal digits");
            writer.newLine();
            for (int gene : averageGenome) {
                writer.write(String.format("%08X", gene));
                writer.newLine();
            }

            JOptionPane.showMessageDialog(
                this,
                "Exported " + averageGenome.length + " average genes to:\n"
                    + outputFile.getAbsolutePath(),
                "Export complete",
                JOptionPane.INFORMATION_MESSAGE
            );
        } catch (IOException exception) {
            JOptionPane.showMessageDialog(
                this,
                "The genes could not be exported:\n" + exception.getMessage(),
                "Export failed",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void updateStatus() {
        populationValue.setText(Integer.toString(worldPanel.getPopulation()));
        generationValue.setText(step + " / " + generation);
        survivalValue.setText(previousSurvivalText);
        worldPanel.setRadioactiveState(radioactivityAllowed, step);
        boolean canAddCreatures = generation == 0;
        add100Button.setEnabled(canAddCreatures);
        add1000Button.setEnabled(canAddCreatures);
        worldPanel.setGeneration(generation);
    }

    public int getGenomeLength() {
        try {
            genomeLength.commitEdit();
        } catch (java.text.ParseException ignored) {
        }
        int value = (Integer) genomeLength.getValue();
        return value % 2 == 0 ? value : value + 1;
    }

    public int getInnerNeurons() { return (Integer) innerNeurons.getValue(); }
    public double getMutationPercent() {
        return ((Number) mutationChance.getValue()).doubleValue();
    }
    public boolean isKillEnabled() { return killEnabled.isSelected(); }
    public boolean isShowReproductiveAreaEnabled() { return showReproductiveAreaEnabled.isSelected(); }

    private static final class EvenSpinnerNumberModel extends SpinnerNumberModel {
        private EvenSpinnerNumberModel(int value, int minimum, int maximum, int stepSize) {
            super(value, minimum, maximum, stepSize);
        }

        @Override
        public void setValue(Object value) {
            if (!(value instanceof Number)) {
                throw new IllegalArgumentException("Genome length must be a number.");
            }
            int evenValue = ((Number) value).intValue();
            if ((evenValue & 1) != 0) {
                evenValue++;
            }
            int minimum = ((Number) getMinimum()).intValue();
            int maximum = ((Number) getMaximum()).intValue();
            super.setValue(Math.max(minimum, Math.min(maximum, evenValue)));
        }
    }

    private static final class WorldPanel extends JPanel {
        private enum Tool { NONE, REPRODUCTION, OBSTACLE }

        private static final Color REPRODUCTION_COLOR = new Color(60, 210, 110, 100);
        private static final Color OBSTACLE_COLOR = new Color(145, 150, 160, 150);
        private static final Color RADIOACTIVE_WALL_COLOR = new Color(255, 70, 35);
        private static final double RADIATION_WALL_HAZARD = 0.08;
        private static final double RADIATION_DISTANCE_SCALE = 32.0;
        private final Random random = new Random();
        private final List<Area> reproductiveAreas = new ArrayList<>();
        private final List<Area> obstacles = new ArrayList<>();
        private final List<Creature> creatures = new ArrayList<>();
        private final double[][] pheromones = new double[WORLD_HEIGHT][WORLD_WIDTH];
        private final boolean[][] activePheromone = new boolean[WORLD_HEIGHT][WORLD_WIDTH];
        private final int[] activePheromoneSquares = new int[WORLD_WIDTH * WORLD_HEIGHT];
        private final Creature[][] creatureGrid = new Creature[WORLD_HEIGHT][WORLD_WIDTH];
        private final boolean[][] occupied = new boolean[WORLD_HEIGHT][WORLD_WIDTH];
        private final ArrayList<Decision> decisions = new ArrayList<>();
        private final ArrayList<Creature> survivorsBuffer = new ArrayList<>();
        private final int[] freeSquareIndices = new int[WORLD_WIDTH * WORLD_HEIGHT];
        private int[] crossoverIndices = new int[0];
        private boolean[] crossoverFromParentA = new boolean[0];
        private Creature[] tickCreatures = new Creature[0];
        private Decision[] tickDecisions = new Decision[0];
        private int[] shuffledDecisionIndices = new int[0];
        private final BrainWorkerPool brainWorkers = new BrainWorkerPool();
        private int activePheromoneCount;
        private volatile RenderSnapshot renderSnapshot = RenderSnapshot.EMPTY;
        private Tool activeTool = Tool.NONE;
        private boolean reproductionCircle;
        private boolean obstacleCircle;
        private boolean showReproductiveArea;
        private boolean gridEnabled;
        private volatile boolean radioactive;
        private volatile int radioactiveStep;
        private boolean awaitingSecondPoint;
        private int generation;
        private Point firstPoint;
        private Point mousePoint;
        private Area visibleReproductionArea;
        private Runnable areaChangedListener = () -> { };

        private WorldPanel() {
            setBackground(new Color(17, 19, 24));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { updateMouse(e); }
                @Override public void mouseDragged(MouseEvent e) { updateMouse(e); }
                @Override public void mouseExited(MouseEvent e) { clearMousePreview(); }
                @Override public void mousePressed(MouseEvent e) { choosePoint(e); }
            };
            addMouseMotionListener(mouse);
            addMouseListener(mouse);
        }

        private synchronized void setGeneration(int generation) {
            this.generation = generation;
            repaint();
        }

        private synchronized int getPopulation() {
            return creatures.size();
        }

        private synchronized void setAreaChangedListener(Runnable listener) {
            areaChangedListener = listener == null ? () -> { } : listener;
        }

        private synchronized void setGridEnabled(boolean enabled) {
            gridEnabled = enabled;
            repaint();
        }

        private void setRadioactiveState(boolean enabled, int generationStep) {
            boolean changed = radioactive != enabled
                || radioactiveStep != generationStep;
            radioactive = enabled;
            radioactiveStep = generationStep;
            if (changed) {
                repaint();
            }
        }

        private synchronized int[] createAverageGenome() {
            if (creatures.isEmpty()) {
                return null;
            }

            int sharedGenomeLength = Integer.MAX_VALUE;
            for (Creature creature : creatures) {
                sharedGenomeLength = Math.min(
                    sharedGenomeLength,
                    creature.genome.length
                );
            }

            int[] averageGenome = new int[sharedGenomeLength];
            int population = creatures.size();
            Creature tieBreaker = creatures.get(0);

            for (int geneIndex = 0; geneIndex < sharedGenomeLength; geneIndex++) {
                int averageGene = 0;
                for (int bit = 0; bit < 32; bit++) {
                    int mask = 1 << bit;
                    int setBitCount = 0;
                    for (Creature creature : creatures) {
                        if ((creature.genome[geneIndex] & mask) != 0) {
                            setBitCount++;
                        }
                    }

                    if (setBitCount * 2 > population
                        || (setBitCount * 2 == population
                            && (tieBreaker.genome[geneIndex] & mask) != 0)) {
                        averageGene |= mask;
                    }
                }
                averageGenome[geneIndex] = averageGene;
            }
            return averageGenome;
        }

        private synchronized void clearCreatures() {
            creatures.clear();
            clearPheromones();
            buildOccupancy();
            captureRenderSnapshot(true, generation);
            repaint();
        }

        private void clearPheromones() {
            for (int i = 0; i < activePheromoneCount; i++) {
                int square = activePheromoneSquares[i];
                int x = square % WORLD_WIDTH;
                int y = square / WORLD_WIDTH;
                pheromones[y][x] = 0.0;
                activePheromone[y][x] = false;
            }
            activePheromoneCount = 0;
        }

        private synchronized void addCreatures(
            int requestedAmount,
            int genomeLength,
            int innerNeuronCount
        ) {
            buildOccupancy();
            int freeCount = collectFreeSquares(true);
            shuffleIntArray(freeSquareIndices, freeCount);
            int amount = Math.min(requestedAmount, freeCount);
            for (int i = 0; i < amount; i++) {
                int square = freeSquareIndices[i];
                creatures.add(new Creature(
                    square % WORLD_WIDTH,
                    square / WORLD_WIDTH,
                    createRandomGenome(genomeLength),
                    innerNeuronCount
                ));
            }
            buildOccupancy();
            captureRenderSnapshot(true, generation);
            repaint();
        }

        private int[] createRandomGenome(int genomeLength) {
            int[] genome = new int[genomeLength];
            for (int i = 0; i < genome.length; i++) {
                genome[i] = random.nextInt();
            }
            return genome;
        }

        private synchronized void advanceCreatures(
            boolean killingEnabled,
            boolean radioactivityEnabled,
            int generationStep
        ) {
            radioactive = radioactivityEnabled;
            radioactiveStep = generationStep;
            int creatureCount = creatures.size();
            ensureTickCapacity(creatureCount);
            creatures.toArray(tickCreatures);

            if (creatureCount < 256 || brainWorkers.getWorkerCount() == 1) {
                Random workerRandom = ThreadLocalRandom.current();
                for (int index = 0; index < creatureCount; index++) {
                    Creature creature = tickCreatures[index];
                    creature.age++;
                    tickDecisions[index] = creature.workOneGameTick(
                        this,
                        occupied,
                        workerRandom
                    );
                }
            } else {
                brainWorkers.evaluate(
                    this,
                    occupied,
                    tickCreatures,
                    tickDecisions,
                    creatureCount
                );
            }

            decisions.clear();
            for (int i = 0; i < creatureCount; i++) {
                if (tickDecisions[i] != null) decisions.add(tickDecisions[i]);
            }

            if (radioactivityEnabled) {
                markRadioactiveDeaths(generationStep);
            }

            if (killingEnabled) {
                shuffleDecisionIndices(decisions.size());
                for (int order = 0; order < decisions.size(); order++) {
                    Decision decision = decisions.get(shuffledDecisionIndices[order]);
                    if (!decision.killForward || decision.creature.killedThisTick) continue;
                    Creature target = creatureAt(
                        decision.creature.x + decision.creature.directionX,
                        decision.creature.y + decision.creature.directionY
                    );
                    if (target != null && target != decision.creature) {
                        target.killedThisTick = true;
                    }
                }
            }

            if (killingEnabled || radioactivityEnabled) {
                for (int i = creatures.size() - 1; i >= 0; i--) {
                    Creature creature = creatures.get(i);
                    if (creature.killedThisTick) {
                        occupied[creature.y][creature.x] = false;
                        creatureGrid[creature.y][creature.x] = null;
                        creatures.remove(i);
                    }
                }
                for (int i = decisions.size() - 1; i >= 0; i--) {
                    if (decisions.get(i).creature.killedThisTick) decisions.remove(i);
                }
            }

            shuffleDecisionIndices(decisions.size());
            for (int order = 0; order < decisions.size(); order++) {
                Decision decision = decisions.get(shuffledDecisionIndices[order]);
                Creature creature = decision.creature;
                occupied[creature.y][creature.x] = false;
                creatureGrid[creature.y][creature.x] = null;
                int nx = creature.x + decision.moveX;
                int ny = creature.y + decision.moveY;
                if (decision.hasMovement
                    && isWorldSquare(nx, ny)
                    && !occupied[ny][nx]
                    && !isInsideObstacle(nx, ny)) {
                    creature.x = nx;
                    creature.y = ny;
                    creature.directionX = decision.moveX;
                    creature.directionY = decision.moveY;
                    creature.lastMoveX = decision.moveX;
                    creature.lastMoveY = decision.moveY;
                } else {
                    creature.lastMoveX = 0;
                    creature.lastMoveY = 0;
                }
                occupied[creature.y][creature.x] = true;
                creatureGrid[creature.y][creature.x] = creature;
                creature.killedThisTick = false;
            }

            evaporatePheromones();
            for (Decision decision : decisions) {
                emitPheromones(decision);
            }
        }

        private void markRadioactiveDeaths(int generationStep) {
            boolean westWall = generationStep < GENERATION_LENGTH / 2;
            for (Creature creature : creatures) {
                int distanceFromWall = westWall
                    ? creature.x
                    : WORLD_WIDTH - 1 - creature.x;

                // Radiation intensity attenuates exponentially with distance.
                // Converting that intensity to a Poisson-event probability
                // produces an exponential survival curve over time as well.
                double hazard = RADIATION_WALL_HAZARD * Math.exp(
                    -distanceFromWall / RADIATION_DISTANCE_SCALE
                );
                double deathChance = 1.0 - Math.exp(-hazard);
                if (random.nextDouble() < deathChance) {
                    creature.killedThisTick = true;
                }
            }
        }

        private void ensureTickCapacity(int count) {
            if (tickCreatures.length < count) {
                tickCreatures = new Creature[count];
                tickDecisions = new Decision[count];
            }
            if (shuffledDecisionIndices.length < count) {
                shuffledDecisionIndices = new int[count];
            }
        }

        private void shuffleDecisionIndices(int count) {
            for (int i = 0; i < count; i++) shuffledDecisionIndices[i] = i;
            for (int i = count - 1; i > 0; i--) {
                int other = random.nextInt(i + 1);
                int temporary = shuffledDecisionIndices[i];
                shuffledDecisionIndices[i] = shuffledDecisionIndices[other];
                shuffledDecisionIndices[other] = temporary;
            }
        }

        private boolean[][] buildOccupancy() {
            for (int y = 0; y < WORLD_HEIGHT; y++) {
                java.util.Arrays.fill(occupied[y], false);
                java.util.Arrays.fill(creatureGrid[y], null);
            }
            for (Creature creature : creatures) {
                occupied[creature.y][creature.x] = true;
                creatureGrid[creature.y][creature.x] = creature;
            }
            return occupied;
        }

        private Creature creatureAt(int x, int y) {
            if (!isWorldSquare(x, y)) return null;
            return creatureGrid[y][x];
        }

        private boolean isWorldSquare(int x, int y) {
            return x >= 0 && x < WORLD_WIDTH && y >= 0 && y < WORLD_HEIGHT;
        }

        private void evaporatePheromones() {
            int index = 0;
            while (index < activePheromoneCount) {
                int square = activePheromoneSquares[index];
                int x = square % WORLD_WIDTH;
                int y = square / WORLD_WIDTH;
                double value = pheromones[y][x] - 0.05;
                if (value <= 0.0) {
                    pheromones[y][x] = 0.0;
                    activePheromone[y][x] = false;
                    activePheromoneSquares[index] =
                        activePheromoneSquares[--activePheromoneCount];
                } else {
                    pheromones[y][x] = value;
                    index++;
                }
            }
        }

        private void emitPheromones(Decision decision) {
            if (decision.pheromoneCount <= 0) return;
            int availableDirections = 0xFF;
            int emitted = 0;
            while (availableDirections != 0 && emitted < decision.pheromoneCount) {
                int remaining = Integer.bitCount(availableDirections);
                int selected = random.nextInt(remaining);
                int direction = -1;
                for (int bit = 0; bit < 8; bit++) {
                    if ((availableDirections & (1 << bit)) != 0 && selected-- == 0) {
                        direction = bit;
                        break;
                    }
                }
                availableDirections &= ~(1 << direction);
                int dx = direction % 3 - 1;
                int dy = direction / 3 - 1;
                if (direction >= 4) {
                    int encoded = direction + 1;
                    dx = encoded % 3 - 1;
                    dy = encoded / 3 - 1;
                }
                int x = decision.creature.x + dx;
                int y = decision.creature.y + dy;
                if (!isWorldSquare(x, y)) continue;
                pheromones[y][x] = 1.0;
                if (!activePheromone[y][x]) {
                    activePheromone[y][x] = true;
                    activePheromoneSquares[activePheromoneCount++] = y * WORLD_WIDTH + x;
                }
                emitted++;
            }
        }

        private synchronized int beginNextGeneration(
            double mutationChance,
            int requestedPopulationTarget
        ) {
            ArrayList<Creature> survivors = survivorsBuffer;
            survivors.clear();
            for (Creature creature : creatures) {
                if (isInsideReproductiveArea(creature.x, creature.y)) {
                    creature.age = 0;
                    survivors.add(creature);
                }
            }
            int survivorCount = survivors.size();
            creatures.clear();

            if (survivors.isEmpty()) {
                return 0;
            }

            creatures.addAll(survivors);
            int availableSquares = collectFreeSquares(false);
            int nextGenerationTarget = Math.min(
                Math.max(requestedPopulationTarget, survivors.size()),
                availableSquares
            );

            while (creatures.size() < nextGenerationTarget) {
                Creature parentA = survivors.get(random.nextInt(survivors.size()));
                Creature parentB = parentA;
                if (survivors.size() > 1) {
                    while (parentB == parentA) {
                        parentB = survivors.get(random.nextInt(survivors.size()));
                    }
                }
                int[] childGenome = inheritGenome(parentA.genome, parentB.genome);
                if (mutationChance > 0.0) {
                    mutateGenome(childGenome, mutationChance);
                }
                creatures.add(new Creature(
                    0,
                    0,
                    childGenome,
                    parentA.innerOutputs.length
                ));
            }

            teleportCreaturesToFreeSquares();
            return survivorCount;
        }

        private synchronized void restartCurrentGeneration(
            double mutationChance,
            int requestedPopulationTarget
        ) {
            if (creatures.isEmpty()) {
                return;
            }

            ArrayList<Creature> parents = survivorsBuffer;
            parents.clear();
            parents.addAll(creatures);

            int availableSquares = collectFreeSquares(false);
            int populationTarget = Math.min(
                Math.max(requestedPopulationTarget, creatures.size()),
                availableSquares
            );

            while (creatures.size() < populationTarget) {
                Creature parentA = parents.get(random.nextInt(parents.size()));
                Creature parentB = parentA;

                if (parents.size() > 1) {
                    while (parentB == parentA) {
                        parentB = parents.get(random.nextInt(parents.size()));
                    }
                }

                int[] childGenome = inheritGenome(
                    parentA.genome,
                    parentB.genome
                );

                if (mutationChance > 0.0) {
                    mutateGenome(childGenome, mutationChance);
                }

                creatures.add(new Creature(
                    0,
                    0,
                    childGenome,
                    parentA.innerOutputs.length
                ));
            }

            clearPheromones();
            teleportCreaturesToFreeSquares();
        }

        private int[] inheritGenome(int[] parentA, int[] parentB) {
            int length = Math.min(parentA.length, parentB.length);
            if ((length & 1) != 0) {
                length--;
            }
            int[] child = new int[length];
            ensureCrossoverCapacity(length);
            java.util.Arrays.fill(crossoverFromParentA, 0, length, false);
            for (int i = 0; i < length; i++) crossoverIndices[i] = i;
            for (int i = length - 1; i > 0; i--) {
                int other = random.nextInt(i + 1);
                int temporary = crossoverIndices[i];
                crossoverIndices[i] = crossoverIndices[other];
                crossoverIndices[other] = temporary;
            }
            for (int i = 0; i < length / 2; i++) {
                crossoverFromParentA[crossoverIndices[i]] = true;
            }
            for (int i = 0; i < length; i++) {
                child[i] = crossoverFromParentA[i] ? parentA[i] : parentB[i];
            }
            return child;
        }

        private void ensureCrossoverCapacity(int length) {
            if (crossoverIndices.length < length) {
                crossoverIndices = new int[length];
                crossoverFromParentA = new boolean[length];
            }
        }

        private void mutateGenome(int[] genome, double mutationChance) {
            for (int i = 0; i < genome.length; i++) {
                if (random.nextDouble() < mutationChance) {
                    genome[i] ^= 1 << random.nextInt(32);
                }
            }
        }

        private void teleportCreaturesToFreeSquares() {
            int freeCount = collectFreeSquares(false);
            shuffleIntArray(freeSquareIndices, freeCount);
            int placed = Math.min(creatures.size(), freeCount);
            while (creatures.size() > placed) {
                creatures.remove(creatures.size() - 1);
            }
            for (int i = 0; i < placed; i++) {
                int square = freeSquareIndices[i];
                Creature creature = creatures.get(i);
                creature.x = square % WORLD_WIDTH;
                creature.y = square / WORLD_WIDTH;
                creature.age = 0;
                creature.resetForGeneration();
            }
            buildOccupancy();
        }

        private int collectFreeSquares(boolean excludeOccupied) {
            int count = 0;
            for (int y = 0; y < WORLD_HEIGHT; y++) {
                for (int x = 0; x < WORLD_WIDTH; x++) {
                    if (!isInsideObstacle(x, y)
                        && (!excludeOccupied || !occupied[y][x])) {
                        freeSquareIndices[count++] = y * WORLD_WIDTH + x;
                    }
                }
            }
            return count;
        }

        private void shuffleIntArray(int[] values, int count) {
            for (int i = count - 1; i > 0; i--) {
                int other = random.nextInt(i + 1);
                int temporary = values[i];
                values[i] = values[other];
                values[other] = temporary;
            }
        }

        private boolean isInsideObstacle(int x, int y) {
            for (Area obstacle : obstacles) {
                if (obstacle.contains(x, y)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isInsideReproductiveArea(int x, int y) {
            if (isInsideObstacle(x, y)) {
                return false;
            }
            for (Area area : reproductiveAreas) {
                if (area.contains(x, y)) {
                    return true;
                }
            }
            return false;
        }

        private synchronized void selectTool(Tool tool) {
            activeTool = tool;
            awaitingSecondPoint = false;
            firstPoint = null;
            mousePoint = null;
            repaint();
        }

        private synchronized boolean toggleShape(Tool tool) {
            if (tool == Tool.REPRODUCTION) {
                reproductionCircle = !reproductionCircle;
                return reproductionCircle;
            }
            obstacleCircle = !obstacleCircle;
            return obstacleCircle;
        }

        private synchronized void setShowReproductiveArea(boolean show) {
            showReproductiveArea = show;
            repaint();
        }

        private synchronized void resetReproductiveAreas() {
            reproductiveAreas.clear();
            visibleReproductionArea = null;
            cancelSelectionFor(Tool.REPRODUCTION);
            notifyAreaChanged();
            repaint();
        }

        private synchronized void resetObstacles() {
            obstacles.clear();
            cancelSelectionFor(Tool.OBSTACLE);
            notifyAreaChanged();
            repaint();
        }

        private void notifyAreaChanged() {
            SwingUtilities.invokeLater(areaChangedListener);
        }

        private synchronized String createAreaCode() {
            // E1 is the format version. Every area after it occupies nine hex
            // digits: type, start x/y, and end x/y.
            StringBuilder code = new StringBuilder("E1");
            for (Area area : reproductiveAreas) {
                appendHexArea(code, area.circle ? 1 : 0, area);
            }
            for (Area area : obstacles) {
                appendHexArea(code, area.circle ? 3 : 2, area);
            }
            return code.toString();
        }

        private static void appendHexArea(
            StringBuilder code,
            int type,
            Area area
        ) {
            code.append(Integer.toHexString(type).toUpperCase(java.util.Locale.ROOT));
            appendHexByte(code, area.start.x);
            appendHexByte(code, area.start.y);
            appendHexByte(code, area.end.x);
            appendHexByte(code, area.end.y);
        }

        private static void appendHexByte(StringBuilder code, int value) {
            String hex = Integer.toHexString(value & 0xFF)
                .toUpperCase(java.util.Locale.ROOT);
            if (hex.length() < 2) {
                code.append('0');
            }
            code.append(hex);
        }

        private synchronized void applyAreaCode(String code) {
            ArrayList<Area> newReproductiveAreas = new ArrayList<>();
            ArrayList<Area> newObstacles = new ArrayList<>();
            String compact = code.replaceAll("\\s+", "")
                .toUpperCase(java.util.Locale.ROOT);
            if (!compact.startsWith("E1")) {
                throw new IllegalArgumentException(
                    "The map code must begin with E1."
                );
            }

            String records = compact.substring(2);
            if (records.length() % 9 != 0) {
                throw new IllegalArgumentException(
                    "The map code is incomplete or has extra characters."
                );
            }

            for (int offset = 0; offset < records.length(); offset += 9) {
                int type = Character.digit(records.charAt(offset), 16);
                if (type < 0 || type > 3) {
                    throw mapCodeError(offset, "unknown area type");
                }

                int x1 = parseHexByte(records, offset + 1, offset);
                int y1 = parseHexByte(records, offset + 3, offset);
                int x2 = parseHexByte(records, offset + 5, offset);
                int y2 = parseHexByte(records, offset + 7, offset);
                if (x1 >= WORLD_WIDTH || x2 >= WORLD_WIDTH
                    || y1 >= WORLD_HEIGHT || y2 >= WORLD_HEIGHT) {
                    throw mapCodeError(offset, "a coordinate is outside the 128 x 128 world");
                }

                Area area = new Area(
                    new Point(x1, y1),
                    new Point(x2, y2),
                    type == 1 || type == 3
                );
                (type < 2 ? newReproductiveAreas : newObstacles).add(area);
            }

            reproductiveAreas.clear();
            reproductiveAreas.addAll(newReproductiveAreas);
            obstacles.clear();
            obstacles.addAll(newObstacles);
            visibleReproductionArea = null;
            awaitingSecondPoint = false;
            firstPoint = null;
            mousePoint = null;
            buildOccupancy();
            repaint();
        }

        private static int parseHexByte(
            String records,
            int start,
            int recordOffset
        ) {
            try {
                return Integer.parseInt(records.substring(start, start + 2), 16);
            } catch (NumberFormatException exception) {
                throw mapCodeError(recordOffset, "contains a non-hexadecimal character");
            }
        }

        private static IllegalArgumentException mapCodeError(
            int recordOffset,
            String message
        ) {
            return new IllegalArgumentException(
                "Area " + (recordOffset / 9 + 1) + " " + message + "."
            );
        }

        private void cancelSelectionFor(Tool tool) {
            if (activeTool == tool) {
                awaitingSecondPoint = false;
                firstPoint = null;
                mousePoint = null;
            }
        }

        private synchronized void updateMouse(MouseEvent e) {
            mousePoint = toWorldPoint(e.getPoint());
            repaint();
        }

        private synchronized void clearMousePreview() {
            mousePoint = null;
            repaint();
        }

        private synchronized void choosePoint(MouseEvent e) {
            if (activeTool == Tool.NONE) return;
            Point selected = toWorldPoint(e.getPoint());
            if (selected == null) return;
            if (!awaitingSecondPoint) {
                firstPoint = selected;
                awaitingSecondPoint = true;
            } else {
                Area area = new Area(firstPoint, selected, isCircleMode());
                if (activeTool == Tool.REPRODUCTION) {
                    showReproductionArea(area);
                } else {
                    obstacles.add(area);
                    buildOccupancy();
                    notifyAreaChanged();
                }
                awaitingSecondPoint = false;
                firstPoint = null;
            }
            repaint();
        }

        private boolean isCircleMode() {
            return activeTool == Tool.REPRODUCTION ? reproductionCircle : obstacleCircle;
        }

        private void showReproductionArea(Area area) {
            reproductiveAreas.add(area);
            visibleReproductionArea = area;
            notifyAreaChanged();
            Timer timer = new Timer(1000, e -> {
                clearTemporaryReproductionArea(area);
                ((Timer) e.getSource()).stop();
            });
            timer.setRepeats(false);
            timer.start();
        }

        private synchronized void clearTemporaryReproductionArea(Area area) {
            if (visibleReproductionArea == area) {
                visibleReproductionArea = null;
            }
            repaint();
        }

        private Rectangle worldBounds() {
            int margin = 24;
            int headerHeight = 34;
            int availableWidth = Math.max(1, getWidth() - margin * 2);
            int availableHeight = Math.max(1, getHeight() - margin * 2 - headerHeight);
            int size = Math.min(availableWidth, availableHeight);
            int x = (getWidth() - size) / 2;
            int y = headerHeight + margin + (availableHeight - size) / 2;
            return new Rectangle(x, y, size, size);
        }

        private Point toWorldPoint(Point pixel) {
            Rectangle bounds = worldBounds();
            if (!bounds.contains(pixel)) return null;
            int x = Math.min(WORLD_WIDTH - 1,
                (int) ((pixel.x - bounds.x) * WORLD_WIDTH / (double) bounds.width));
            int y = Math.min(WORLD_HEIGHT - 1,
                (int) ((pixel.y - bounds.y) * WORLD_HEIGHT / (double) bounds.height));
            return new Point(x, y);
        }

        private synchronized void captureRenderSnapshot(
            boolean includeCreatures,
            int displayedGeneration
        ) {
            int size = includeCreatures ? creatures.size() : 0;
            int[] positions = new int[size];
            Color[] colors = new Color[size];
            for (int i = 0; i < size; i++) {
                Creature creature = creatures.get(i);
                positions[i] = creature.y * WORLD_WIDTH + creature.x;
                colors[i] = creature.color;
            }
            renderSnapshot = new RenderSnapshot(
                positions,
                colors,
                displayedGeneration
            );
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int margin = 24;
            g.setColor(Color.WHITE);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 18f));
            RenderSnapshot snapshot = renderSnapshot;
            String text = "Gen " + snapshot.generation;
            g.drawString(text, (getWidth() - g.getFontMetrics().stringWidth(text)) / 2, margin);

            Rectangle bounds = worldBounds();
            g.setColor(new Color(8, 10, 13));
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);

            // Draw the grid behind every area. Reproductive and obstacle
            // shading therefore hides grid lines inside its occupied cells.
            if (gridEnabled) {
                drawGrid(g, bounds);
            }

            // Reproductive areas are drawn first so obstacles always cover
            // them wherever the two types overlap.
            Area reproductionPreview = awaitingSecondPoint
                && activeTool == Tool.REPRODUCTION
                && firstPoint != null
                && mousePoint != null
                    ? new Area(firstPoint, mousePoint, isCircleMode())
                    : null;
            if (showReproductiveArea) {
                drawAreasAsUnion(
                    g,
                    reproductiveAreas,
                    reproductionPreview,
                    bounds,
                    REPRODUCTION_COLOR
                );
            } else if (visibleReproductionArea != null) {
                drawAreasAsUnion(
                    g,
                    java.util.Collections.singletonList(visibleReproductionArea),
                    reproductionPreview,
                    bounds,
                    REPRODUCTION_COLOR
                );
            } else if (reproductionPreview != null) {
                drawArea(g, reproductionPreview, bounds, REPRODUCTION_COLOR);
            }

            Area obstaclePreview = awaitingSecondPoint
                && activeTool == Tool.OBSTACLE
                && firstPoint != null
                && mousePoint != null
                    ? new Area(firstPoint, mousePoint, isCircleMode())
                    : null;
            drawAreasAsUnion(
                g,
                obstacles,
                obstaclePreview,
                bounds,
                OBSTACLE_COLOR
            );

            drawCreatures(g, bounds, snapshot);
            g.setColor(new Color(110, 200, 255));
            g.drawRect(
                bounds.x,
                bounds.y,
                bounds.width,
                bounds.height
            );
            if (radioactive) {
                drawRadioactiveWall(g, bounds);
            }
            g.dispose();
        }

        private void drawRadioactiveWall(Graphics2D g, Rectangle bounds) {
            Stroke oldStroke = g.getStroke();
            boolean westWall = radioactiveStep < GENERATION_LENGTH / 2;
            int wallX = westWall ? bounds.x : bounds.x + bounds.width;

            g.setColor(new Color(255, 70, 35, 70));
            g.setStroke(new BasicStroke(12.0f));
            g.drawLine(wallX, bounds.y, wallX, bounds.y + bounds.height);

            g.setColor(RADIOACTIVE_WALL_COLOR);
            g.setStroke(new BasicStroke(4.0f));
            g.drawLine(wallX, bounds.y, wallX, bounds.y + bounds.height);
            g.setStroke(oldStroke);
        }

        private void drawCreatures(
            Graphics2D g,
            Rectangle bounds,
            RenderSnapshot snapshot
        ) {
            double sx = bounds.width / (double) WORLD_WIDTH;
            double sy = bounds.height / (double) WORLD_HEIGHT;
            for (int i = 0; i < snapshot.positions.length; i++) {
                int position = snapshot.positions[i];
                int creatureX = position % WORLD_WIDTH;
                int creatureY = position / WORLD_WIDTH;
                g.setColor(snapshot.colors[i]);
                int x = bounds.x + (int) Math.floor(creatureX * sx);
                int y = bounds.y + (int) Math.floor(creatureY * sy);
                int width = Math.max(1, (int) Math.ceil(sx));
                int height = Math.max(1, (int) Math.ceil(sy));
                g.fillRect(x, y, width, height);
            }
        }

        private static final class RenderSnapshot {
            private static final RenderSnapshot EMPTY =
                new RenderSnapshot(new int[0], new Color[0], 0);

            private final int[] positions;
            private final Color[] colors;
            private final int generation;

            private RenderSnapshot(int[] positions, Color[] colors, int generation) {
                this.positions = positions;
                this.colors = colors;
                this.generation = generation;
            }
        }

        private void drawArea(Graphics2D g, Area area, Rectangle bounds, Color color) {
            drawAreasAsUnion(
                g,
                java.util.Collections.singletonList(area),
                null,
                bounds,
                color
            );
        }

        private void drawAreasAsUnion(
            Graphics2D g,
            List<Area> areas,
            Area extraArea,
            Rectangle bounds,
            Color color
        ) {
            double sx = bounds.width / (double) WORLD_WIDTH;
            double sy = bounds.height / (double) WORLD_HEIGHT;
            g.setColor(color);

            // Paint each covered cell only once. Overlapping areas therefore
            // keep exactly the same shade instead of becoming darker.
            for (int worldY = 0; worldY < WORLD_HEIGHT; worldY++) {
                int runStart = -1;
                for (int worldX = 0; worldX <= WORLD_WIDTH; worldX++) {
                    boolean inside = worldX < WORLD_WIDTH
                        && isInsideAnyArea(areas, extraArea, worldX, worldY);
                    if (inside && runStart < 0) {
                        runStart = worldX;
                    } else if (!inside && runStart >= 0) {
                        int pixelLeft = bounds.x
                            + (int) Math.floor(runStart * sx);
                        int pixelRight = bounds.x
                            + (int) Math.ceil(worldX * sx);
                        int pixelTop = bounds.y
                            + (int) Math.floor(worldY * sy);
                        int pixelBottom = bounds.y
                            + (int) Math.ceil((worldY + 1) * sy);
                        g.fillRect(
                            pixelLeft,
                            pixelTop,
                            Math.max(1, pixelRight - pixelLeft),
                            Math.max(1, pixelBottom - pixelTop)
                        );
                        runStart = -1;
                    }
                }
            }
        }

        private static boolean isInsideAnyArea(
            List<Area> areas,
            Area extraArea,
            int x,
            int y
        ) {
            if (extraArea != null && extraArea.contains(x, y)) {
                return true;
            }
            for (Area area : areas) {
                if (area.contains(x, y)) {
                    return true;
                }
            }
            return false;
        }

        private void drawGrid(Graphics2D g, Rectangle bounds) {
            Stroke oldStroke = g.getStroke();
            g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_OFF
            );

            for (int gridX = 1; gridX < WORLD_WIDTH; gridX++) {
                boolean zeroAxis = gridX == WORLD_WIDTH / 2;
                boolean major = gridX % 8 == 0;
                setGridLineStyle(g, zeroAxis, major);
                int pixelX = bounds.x + (int) Math.round(
                    gridX * bounds.width / (double) WORLD_WIDTH
                );
                g.drawLine(pixelX, bounds.y, pixelX, bounds.y + bounds.height);
            }

            for (int gridY = 1; gridY < WORLD_HEIGHT; gridY++) {
                boolean zeroAxis = gridY == WORLD_HEIGHT / 2;
                boolean major = gridY % 8 == 0;
                setGridLineStyle(g, zeroAxis, major);
                int pixelY = bounds.y + (int) Math.round(
                    gridY * bounds.height / (double) WORLD_HEIGHT
                );
                g.drawLine(bounds.x, pixelY, bounds.x + bounds.width, pixelY);
            }

            g.setStroke(oldStroke);
            g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            );
        }

        private static void setGridLineStyle(
            Graphics2D g,
            boolean zeroAxis,
            boolean major
        ) {
            if (zeroAxis) {
                g.setColor(new Color(185, 195, 210, 210));
                g.setStroke(new BasicStroke(2.0f));
            } else if (major) {
                g.setColor(new Color(115, 125, 140, 155));
                g.setStroke(new BasicStroke(1.1f));
            } else {
                g.setColor(new Color(75, 82, 94, 95));
                g.setStroke(new BasicStroke(0.6f));
            }
        }

        private static final class BrainWorkerPool {
            private final int workerCount = Math.max(
                1,
                Math.min(
                    8,
                    Runtime.getRuntime().availableProcessors() - 1
                )
            );
            private final Phaser phaser = new Phaser(workerCount + 1);
            private volatile WorldPanel world;
            private volatile boolean[][] occupied;
            private volatile Creature[] creatures;
            private volatile Decision[] decisions;
            private volatile int creatureCount;

            private BrainWorkerPool() {
                for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
                    final int index = workerIndex;
                    Thread worker = new Thread(
                        () -> runWorker(index),
                        "evolution-brain-" + workerIndex
                    );
                    worker.setDaemon(true);
                    worker.start();
                }
            }

            private int getWorkerCount() {
                return workerCount;
            }

            private void evaluate(
                WorldPanel world,
                boolean[][] occupied,
                Creature[] creatures,
                Decision[] decisions,
                int creatureCount
            ) {
                this.world = world;
                this.occupied = occupied;
                this.creatures = creatures;
                this.decisions = decisions;
                this.creatureCount = creatureCount;

                phaser.arriveAndAwaitAdvance();
                phaser.arriveAndAwaitAdvance();
            }

            private void runWorker(int workerIndex) {
                Random workerRandom = ThreadLocalRandom.current();

                while (true) {
                    phaser.arriveAndAwaitAdvance();

                    int count = creatureCount;
                    int start = workerIndex * count / workerCount;
                    int end = (workerIndex + 1) * count / workerCount;

                    for (int creatureIndex = start; creatureIndex < end; creatureIndex++) {
                        Creature creature = creatures[creatureIndex];
                        creature.age++;
                        decisions[creatureIndex] = creature.workOneGameTick(
                            world,
                            occupied,
                            workerRandom
                        );
                    }

                    phaser.arriveAndAwaitAdvance();
                }
            }
        }

        private static final class Decision {
            private final Creature creature;
            private int moveX;
            private int moveY;
            private boolean hasMovement;
            private boolean killForward;
            private int pheromoneCount;

            private Decision(Creature creature) {
                this.creature = creature;
            }

            private void reset() {
                moveX = 0;
                moveY = 0;
                hasMovement = false;
                killForward = false;
                pheromoneCount = 0;
            }
        }

        private static final class Connection {
            private final boolean sourceInner;
            private final int sourceIndex;
            private final int sinkIndex;
            private final double weight;

            private Connection(
                boolean sourceInner,
                int sourceIndex,
                int sinkIndex,
                double weight
            ) {
                this.sourceInner = sourceInner;
                this.sourceIndex = sourceIndex;
                this.sinkIndex = sinkIndex;
                this.weight = weight;
            }
        }

        private static final class Creature {
            private static final int SENSOR_COUNT = 21;
            private static final int ACTION_COUNT = 11;

            private static final int AGE = 0;
            private static final int RND = 1;
            private static final int BLR = 2;
            private static final int BFD = 3;
            private static final int POP = 4;
            private static final int LMY = 5;
            private static final int LMX = 6;
            private static final int LPF = 7;
            private static final int LBF = 8;
            private static final int BDY = 9;
            private static final int BDX = 10;
            private static final int GEN = 11;
            private static final int LY = 12;
            private static final int LX = 13;
            private static final int BD = 14;
            private static final int OSC_SENSOR = 15;
            private static final int PGR = 16;
            private static final int PGF = 17;
            private static final int SGR = 18;
            private static final int SGF = 19;
            private static final int SD = 20;

            private static final int SSD = 0;
            private static final int KILL = 1;
            private static final int OSC_ACTION = 2;
            private static final int SG = 3;
            private static final int RES = 4;
            private static final int MFD = 5;
            private static final int MRN = 6;
            private static final int MRV = 7;
            private static final int MRL = 8;
            private static final int MX = 9;
            private static final int MY = 10;

            private int x;
            private int y;
            private int age;
            private int directionX;
            private int directionY;
            private int lastMoveX;
            private int lastMoveY;
            private int sensorDistance = 1;
            private int oscillatorPeriod = 20;
            private int randomPeriod = 1;
            private int nextRandomTick;
            private int brainTick;
            private double randomSignal;
            private double responsiveness = 1.0;
            private double responsivenessAccumulator;
            private double previousLeftRightPopulation;
            private double previousForwardPopulation;
            private double previousLeftRightPheromone;
            private double previousForwardPheromone;
            private final int[] genome;
            private final double[] innerOutputs;
            private final double[] innerSums;
            private final double[] newInnerOutputs;
            private final double[] sensors = new double[SENSOR_COUNT];
            private final double[] actionSums = new double[ACTION_COUNT];
            private final double[] actions = new double[ACTION_COUNT];
            private final boolean[] fired = new boolean[ACTION_COUNT];
            private final Connection[] innerConnections;
            private final Connection[] actionConnections;
            private final Decision reusableDecision;
            private final Color color;
            private boolean killedThisTick;

            private Creature(int x, int y, int[] genome, int innerNeuronCount) {
                this.x = x;
                this.y = y;
                this.genome = genome;
                innerOutputs = new double[Math.max(0, innerNeuronCount)];
                innerSums = new double[innerOutputs.length];
                newInnerOutputs = new double[innerOutputs.length];
                Connection[][] decodedConnections = decodeConnections(genome, innerOutputs.length);
                innerConnections = decodedConnections[0];
                actionConnections = decodedConnections[1];
                reusableDecision = new Decision(this);
                color = colorFromGenome(genome);
            }

            private void resetForGeneration() {
                age = 0;
                directionX = 0;
                directionY = 0;
                lastMoveX = 0;
                lastMoveY = 0;
                responsivenessAccumulator = 0.0;
                previousLeftRightPopulation = 0.0;
                previousForwardPopulation = 0.0;
                previousLeftRightPheromone = 0.0;
                previousForwardPheromone = 0.0;
                killedThisTick = false;
                java.util.Arrays.fill(innerOutputs, 0.0);
            }

            private Decision workOneGameTick(
                WorldPanel world,
                boolean[][] occupied,
                Random random
            ) {
                responsivenessAccumulator += responsiveness;
                if (responsivenessAccumulator < 1.0) {
                    return null;
                }

                int brainRuns = Math.min(2, (int) responsivenessAccumulator);
                responsivenessAccumulator -= brainRuns;
                Decision bestDecision = reusableDecision;
                bestDecision.reset();
                double bestMovementOutput = -1.0;

                for (int run = 0; run < brainRuns; run++) {
                    brainTick++;
                    calculateSensors(world, occupied, random);
                    runNetwork();
                    for (int action = 0; action < ACTION_COUNT; action++) {
                        if (action == MX || action == MY) {
                            fired[action] = Math.abs(actions[action]) > 0.0
                                && random.nextDouble() < Math.abs(actions[action]);
                        } else {
                            fired[action] = actions[action] > 0.0
                                && random.nextDouble() < actions[action];
                        }
                    }

                    if (fired[SSD]) {
                        sensorDistance += actions[SSD] >= 0.5 ? 1 : -1;
                        sensorDistance = Math.max(1, Math.min(6, sensorDistance));
                    }
                    if (fired[OSC_ACTION]) {
                        oscillatorPeriod = Math.max(1, Math.min(10,
                            (int) Math.round(10.0 - actions[OSC_ACTION] * 9.0)));
                    }
                    if (fired[RES]) {
                        responsiveness = Math.max(0.0, Math.min(2.0, actions[RES] * 2.0));
                    }
                    if (fired[KILL] && hasDirection()) {
                        bestDecision.killForward = true;
                    }
                    if (fired[SG]) {
                        bestDecision.pheromoneCount = Math.max(
                            bestDecision.pheromoneCount,
                            Math.max(1, Math.min(8, (int) Math.round(actions[SG] * 8.0)))
                        );
                    }

                    for (int action = MFD; action <= MY; action++) {
                        double movementStrength = action == MX || action == MY
                            ? Math.abs(actions[action])
                            : actions[action];

                        if (!fired[action] || movementStrength <= bestMovementOutput) continue;
                        int movement = movementForAction(action, actions[action], random);
                        if (movement >= 0) {
                            bestMovementOutput = movementStrength;
                            bestDecision.moveX = movement / 3 - 1;
                            bestDecision.moveY = movement % 3 - 1;
                            bestDecision.hasMovement = true;
                        }
                    }
                }
                return bestDecision;
            }

            private void runNetwork() {
                java.util.Arrays.fill(innerSums, 0.0);
                for (Connection connection : innerConnections) {
                    double source = connection.sourceInner
                        ? innerOutputs[connection.sourceIndex]
                        : sensors[connection.sourceIndex];
                    innerSums[connection.sinkIndex] += source * connection.weight;
                }

                for (int i = 0; i < newInnerOutputs.length; i++) {
                    newInnerOutputs[i] = Math.tanh(innerSums[i]);
                }

                java.util.Arrays.fill(actionSums, 0.0);
                for (Connection connection : actionConnections) {
                    double source = connection.sourceInner
                        ? newInnerOutputs[connection.sourceIndex]
                        : sensors[connection.sourceIndex];
                    actionSums[connection.sinkIndex] += source * connection.weight;
                }
                System.arraycopy(
                    newInnerOutputs,
                    0,
                    innerOutputs,
                    0,
                    newInnerOutputs.length
                );

                for (int i = 0; i < actions.length; i++) {
                    actions[i] = Math.tanh(actionSums[i]);
                }
            }

            private static Connection[][] decodeConnections(int[] genome, int innerCount) {
                ArrayList<Connection> inner = new ArrayList<>();
                ArrayList<Connection> action = new ArrayList<>();
                for (int gene : genome) {
                    boolean sourceInner = ((gene >>> 31) & 1) != 0;
                    boolean sinkAction = ((gene >>> 23) & 1) != 0;
                    if (sourceInner && innerCount == 0) continue;
                    if (!sinkAction && innerCount == 0) continue;
                    int sourceId = (gene >>> 24) & 0x7F;
                    int sinkId = (gene >>> 16) & 0x7F;
                    Connection connection = new Connection(
                        sourceInner,
                        sourceInner ? sourceId % innerCount : sourceId % SENSOR_COUNT,
                        sinkAction ? sinkId % ACTION_COUNT : sinkId % innerCount,
                        decodeWeight(gene)
                    );
                    (sinkAction ? action : inner).add(connection);
                }
                return new Connection[][] {
                    inner.toArray(new Connection[0]),
                    action.toArray(new Connection[0])
                };
            }

            private static double decodeWeight(int gene) {
                short signedWeight = (short) (gene & 0xFFFF);
                return signedWeight / 8192.0;
            }

            private void calculateSensors(
                WorldPanel world,
                boolean[][] occupied,
                Random random
            ) {
                double[] values = sensors;
                java.util.Arrays.fill(values, 0.0);
                values[AGE] = clamp01(age / (double) GENERATION_LENGTH);

                if (brainTick >= nextRandomTick) {
                    randomSignal = random.nextDouble();
                    randomPeriod = 1 + random.nextInt(10);
                    nextRandomTick = brainTick + randomPeriod;
                }
                values[RND] = randomSignal;

                int neighborPopulation = countNeighbors(occupied);
                values[POP] = neighborPopulation / 8.0;
                values[LMY] = lastMoveY == 0 ? 0.0 : 1.0;
                values[LMX] = lastMoveX == 0 ? 0.0 : 1.0;
                values[LY] = y / (double) (WORLD_HEIGHT - 1);
                values[LX] = x / (double) (WORLD_WIDTH - 1);
                values[BDY] = Math.max(values[LY], 1.0 - values[LY]);
                values[BDX] = Math.max(values[LX], 1.0 - values[LX]);
                double nearestBorder = Math.min(
                    Math.min(x, WORLD_WIDTH - 1 - x),
                    Math.min(y, WORLD_HEIGHT - 1 - y)
                );
                values[BD] = 1.0 - clamp01(nearestBorder / ((WORLD_WIDTH - 1) / 2.0));
                values[OSC_SENSOR] = brainTick % oscillatorPeriod == 0 ? 1.0 : 0.0;
                values[SD] = nearbyPheromoneDensity(world);

                if (hasDirection()) {
                    int leftX = directionY;
                    int leftY = -directionX;
                    int rightX = -directionY;
                    int rightY = directionX;
                    double leftRightPopulation = occupancyAtDistance(
                        occupied, leftX, leftY, sensorDistance)
                        + occupancyAtDistance(occupied, rightX, rightY, sensorDistance);
                    values[BLR] = leftRightPopulation / 2.0;
                    values[BFD] = occupancyAtDistance(
                        occupied, directionX, directionY, sensorDistance);

                    int forwardCount = 0;
                    for (int distance = 1; distance <= sensorDistance; distance++) {
                        forwardCount += occupancyAtDistance(
                            occupied, directionX, directionY, distance);
                    }
                    values[LPF] = forwardCount / (double) sensorDistance;
                    values[LBF] = forwardCount > 0 ? 1.0 : 0.0;

                    Creature forwardCreature = world.creatureAt(
                        x + directionX,
                        y + directionY
                    );
                    values[GEN] = forwardCreature == null
                        ? 0.0
                        : geneticSimilarity(genome, forwardCreature.genome);

                    double forwardPopulation = forwardCount;
                    values[PGR] = clamp01(Math.abs(
                        leftRightPopulation - previousLeftRightPopulation) / 2.0);
                    values[PGF] = Math.abs(
                        forwardPopulation - previousForwardPopulation) >= 1.0 ? 1.0 : 0.0;
                    previousLeftRightPopulation = leftRightPopulation;
                    previousForwardPopulation = forwardPopulation;

                    double leftRightPheromone = pheromoneAtDistance(
                        world, leftX, leftY, sensorDistance)
                        + pheromoneAtDistance(world, rightX, rightY, sensorDistance);
                    double forwardPheromone = pheromoneAtDistance(
                        world, directionX, directionY, sensorDistance);
                    values[SGR] = clamp01(Math.abs(
                        leftRightPheromone - previousLeftRightPheromone));
                    values[SGF] = clamp01(Math.abs(
                        forwardPheromone - previousForwardPheromone));
                    previousLeftRightPheromone = leftRightPheromone;
                    previousForwardPheromone = forwardPheromone;
                }
            }

            private int countNeighbors(boolean[][] occupied) {
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if ((dx != 0 || dy != 0) && occupiedAt(occupied, x + dx, y + dy)) {
                            count++;
                        }
                    }
                }
                return count;
            }

            private int occupancyAtDistance(boolean[][] occupied, int dx, int dy, int distance) {
                return occupiedAt(occupied, x + dx * distance, y + dy * distance) ? 1 : 0;
            }

            private static boolean occupiedAt(boolean[][] occupied, int x, int y) {
                return x >= 0 && x < WORLD_WIDTH && y >= 0 && y < WORLD_HEIGHT
                    && occupied[y][x];
            }

            private double pheromoneAtDistance(WorldPanel world, int dx, int dy, int distance) {
                int px = x + dx * distance;
                int py = y + dy * distance;
                return world.isWorldSquare(px, py) ? world.pheromones[py][px] : 0.0;
            }

            private double nearbyPheromoneDensity(WorldPanel world) {
                double sum = 0.0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int px = x + dx;
                        int py = y + dy;
                        if (world.isWorldSquare(px, py)) sum += world.pheromones[py][px];
                    }
                }
                return clamp01(sum / 8.0);
            }

            private int movementForAction(int action, double output, Random random) {
                if (action == MFD) {
                    return hasDirection() ? encodeMovement(directionX, directionY) : -1;
                }
                if (action == MRV) {
                    return hasDirection() ? encodeMovement(-directionX, -directionY) : -1;
                }
                if (action == MRL) {
                    if (!hasDirection()) return -1;
                    return output <= 0.5
                        ? encodeMovement(directionY, -directionX)
                        : encodeMovement(-directionY, directionX);
                }
                if (action == MRN) {
                    int dx;
                    int dy;
                    do {
                        dx = random.nextInt(3) - 1;
                        dy = random.nextInt(3) - 1;
                    } while (dx == 0 && dy == 0);
                    return encodeMovement(dx, dy);
                }
                if (action == MX) {
                    return encodeMovement(output < 0.0 ? -1 : 1, 0);
                }
                if (action == MY) {
                    return encodeMovement(0, output < 0.0 ? -1 : 1);
                }
                return -1;
            }

            private static int encodeMovement(int dx, int dy) {
                return (dx + 1) * 3 + (dy + 1);
            }

            private boolean hasDirection() {
                return directionX != 0 || directionY != 0;
            }

            private static double geneticSimilarity(int[] first, int[] second) {
                int length = Math.min(first.length, second.length);
                if (length == 0) return 0.0;
                long equalBits = 0;
                for (int i = 0; i < length; i++) {
                    equalBits += 32 - Integer.bitCount(first[i] ^ second[i]);
                }
                return equalBits / (double) (length * 32L);
            }

            private static double clamp01(double value) {
                return Math.max(0.0, Math.min(1.0, value));
            }

            private static Color colorFromGenome(int[] genome) {
                if (genome.length == 0) {
                    return Color.WHITE;
                }

                long redTotal = 0;
                long greenTotal = 0;
                long blueTotal = 0;

                for (int gene : genome) {
                    redTotal += (gene >>> 24) & 0xFF;
                    greenTotal += (gene >>> 16) & 0xFF;
                    blueTotal += (gene >>> 8) & 0xFF;
                }

                int redAverage = (int) (redTotal / genome.length);
                int greenAverage = (int) (greenTotal / genome.length);
                int blueAverage = (int) (blueTotal / genome.length);

                int red = clampColor(128 + (redAverage - 128) * 3);
                int green = clampColor(128 + (greenAverage - 128) * 3);
                int blue = clampColor(128 + (blueAverage - 128) * 3);

                float[] hsb = Color.RGBtoHSB(red, green, blue, null);

                return Color.getHSBColor(
                    hsb[0],
                    Math.min(1.0f, hsb[1] * 1.25f),
                    1.0f
                );
            }

            private static int clampColor(int value) {
                return Math.max(35, Math.min(255, value));
            }
        }

        private static final class Area {
            private final Point start;
            private final Point end;
            private final boolean circle;

            private Area(Point start, Point end, boolean circle) {
                this.start = new Point(start);
                this.end = new Point(end);
                this.circle = circle;
            }

            private boolean contains(int x, int y) {
                if (circle) {
                    double radius = start.distance(end);
                    return start.distance(x, y) <= radius;
                }
                return x >= Math.min(start.x, end.x)
                    && x <= Math.max(start.x, end.x)
                    && y >= Math.min(start.y, end.y)
                    && y <= Math.max(start.y, end.y);
            }
        }
    }

    public static void main(String[] args) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            System.setProperty("sun.java2d.d3d", "true");
            System.setProperty("sun.java2d.accthreshold", "0");
        } else {
            System.setProperty("sun.java2d.opengl", "true");
        }

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            evolution1 window = new evolution1();
            window.setVisible(true);
        });
    }
}
