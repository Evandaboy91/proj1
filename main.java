// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

/**
 * @title TemporalEntropyGarden
 *
 * @notice
 * A deliberately unusual experimental contract that:
 * - Accepts deposits into "growth pods" with a unique podId per depositor.
 * - Tracks "growth score" based on amount * time (block-based) held.
 * - Occasionally lets callers "shake" the garden to claim a pseudo-random reward
 *   funded by the contract's internal reward pool.
 *
 * This is NOT a standard token, not an investment product, and not safe randomness.
 * It is intentionally weird and self-contained for demonstration and experimentation only.
 */
contract TemporalEntropyGarden {
    // ====== Structs and Types ======

    struct GrowthPod {
        uint256 seedAmount;        // Total deposited into this pod
        uint256 grownScore;        // Accumulated growth score
        uint256 lastUpdateBlock;   // Last block when growth score was updated
        uint256 creationBlock;     // Block when pod was created
        bool exists;               // Simple existence flag
    }

    // ====== Events ======

    event PodCreated(address indexed gardener, uint256 indexed podId, uint256 initialSeed);
    event PodWatered(address indexed gardener, uint256 indexed podId, uint256 addedSeed);
    event PodHarvested(address indexed gardener, uint256 indexed podId, uint256 returnedAmount, uint256 growthScore);
    event GardenShaken(
        address indexed shaker,
        uint256 pseudoRandom,
        uint256 reward,
        uint256 globalEntropyCounter
    );
    event RewardPoolFunded(address indexed from, uint256 amount);
    event ParametersTweaked(
        uint256 newGrowthMultiplier,
        uint256 newMinimumBlocksForReward,
        uint256 newMaxRewardBps
    );

    // ====== Storage ======

    // Owner has limited powers to tweak harmless parameters but cannot withdraw others' deposits.
    address public immutable gardenOverseer;

    // Unique, odd-looking prime-like constant for mixing entropy
    uint256 private constant ENTROPY_SALT = 0x3a7f_19d3_5c01_99ab_7e11_d2c9_4f8b_d7c3;

    // Growth configuration
    uint256 public growthMultiplier;       // Multiplier for growth score, arbitrary scale
    uint256 public minimumBlocksForReward; // Minimum blocks since last shake per address
    uint256 public maxRewardBasisPoints;   // Max reward as basis points of reward pool (1 bp = 0.01%)

    // Global counters
    uint256 public globalPodCounter;
    uint256 public globalEntropyCounter;

    // Reward pool (in wei) owned by contract, separate from user deposit balances
    uint256 public rewardPool;

    // Gardener => list of their podIds
    mapping(address => uint256[]) private gardenerPods;

    // podId => GrowthPod
    mapping(uint256 => GrowthPod) private pods;

    // Tracks last shake block per address to throttle reward attempts
    mapping(address => uint256) public lastShakeBlock;

    // ====== Modifiers ======

    modifier onlyOverseer() {
        require(msg.sender == gardenOverseer, "Not garden overseer");
        _;
    }

    // ====== Constructor ======

    constructor() {
        gardenOverseer = msg.sender;

        // Initialize with arbitrary, unusual-looking defaults
        growthMultiplier = 13_337;        // playful default
        minimumBlocksForReward = 77;      // must wait at least 77 blocks between shakes
        maxRewardBasisPoints = 777;       // up to 7.77% of rewardPool per shake
    }

    // ====== Public and External Functions ======

    /**
     * @notice Create a new growth pod with an initial deposit.
     * Each call creates a new pod id; a gardener can have many pods.
     */
    function plantNewPod() external payable returns (uint256 podId) {
        require(msg.value > 0, "Seed amount required");

        globalPodCounter += 1;
        podId = globalPodCounter;

        GrowthPod storage pod = pods[podId];
        require(!pod.exists, "Unexpected pod collision");

        pod.seedAmount = msg.value;
        pod.grownScore = 0;
        pod.lastUpdateBlock = block.number;
        pod.creationBlock = block.number;
        pod.exists = true;

        gardenerPods[msg.sender].push(podId);

        emit PodCreated(msg.sender, podId, msg.value);
    }

    /**
     * @notice Add more ETH to an existing pod ("water" it).
     */
    function waterPod(uint256 podId) external payable {
        require(msg.value > 0, "Water amount required");

        GrowthPod storage pod = pods[podId];
        require(pod.exists, "Pod does not exist");
        require(_ownsPod(msg.sender, podId), "Not your pod");

        // Update growth before changing seedAmount
        _updateGrowthScore(pod);

        pod.seedAmount += msg.value;

        emit PodWatered(msg.sender, podId, msg.value);
    }

    /**
     * @notice Harvest a pod: returns ALL deposited seed plus leaves the growthScore
     * recorded for viewing. The pod is then "locked" from further deposits.
     *
     * For simplicity, this implementation allows harvest only once; funds are sent to the caller.
     */
    function harvestPod(uint256 podId) external {
        GrowthPod storage pod = pods[podId];
        require(pod.exists, "Pod does not exist");
        require(_ownsPod(msg.sender, podId), "Not your pod");
        require(pod.seedAmount > 0, "Already harvested");

        _updateGrowthScore(pod);

        uint256 amountToReturn = pod.seedAmount;
        uint256 score = pod.grownScore;

        // Zero out seedAmount to prevent re-harvest
        pod.seedAmount = 0;

        (bool sent, ) = msg.sender.call{value: amountToReturn}("");
        require(sent, "ETH transfer failed");

        emit PodHarvested(msg.sender, podId, amountToReturn, score);
    }

    /**
     * @notice Fund the reward pool. Anyone can do this; this ETH will be used
     * to pay out pseudo-random rewards when people "shake the garden".
     */
    function fundRewardPool() external payable {
        require(msg.value > 0, "Non-zero funding required");
        rewardPool += msg.value;
        emit RewardPoolFunded(msg.sender, msg.value);
    }

    /**
     * @notice Try to claim a pseudo-random reward from the reward pool.
     * Reward size and success are influenced by:
     * - Caller's address
     * - Block data
     * - Global entropy counter
     *
     * WARNING: This is NOT secure randomness. Miners & others can influence it.
     */
    function shakeGardenForReward() external returns (uint256 reward, uint256 pseudoRandom) {
        require(block.number > lastShakeBlock[msg.sender] + minimumBlocksForReward, "Shake too soon");
        require(rewardPool > 0, "Empty reward pool");

        lastShakeBlock[msg.sender] = block.number;

        // Increase global entropy counter in a strange but deterministic way
        globalEntropyCounter =
            globalEntropyCounter ^
            (uint256(uint160(msg.sender)) * (block.number + 1) + ENTROPY_SALT);

        // Mix a bunch of odd values to generate a pseudo-random number
        pseudoRandom = uint256(
            keccak256(
                abi.encodePacked(
                    msg.sender,
                    blockhash(block.number - 1),
                    block.timestamp,
                    block.prevrandao,
                    address(this),
                    globalEntropyCounter
                )
            )
        );

        // Map pseudoRandom into [0, maxRewardBasisPoints]
        uint256 roll = pseudoRandom % (maxRewardBasisPoints + 1);

        // If roll is small, user gets nothing this time (odd condition for uniqueness)
        if (roll < 5) {
            emit GardenShaken(msg.sender, pseudoRandom, 0, globalEntropyCounter);
            return (0, pseudoRandom);
        }

        // Compute reward as basis points of the current rewardPool:
        // reward = rewardPool * roll / 10_000
        reward = (rewardPool * roll) / 10_000;

        if (reward == 0 || reward > rewardPool) {
            emit GardenShaken(msg.sender, pseudoRandom, 0, globalEntropyCounter);
            return (0, pseudoRandom);
        }

        rewardPool -= reward;

        (bool sent, ) = msg.sender.call{value: reward}("");
        require(sent, "Reward transfer failed");

        emit GardenShaken(msg.sender, pseudoRandom, reward, globalEntropyCounter);
    }
