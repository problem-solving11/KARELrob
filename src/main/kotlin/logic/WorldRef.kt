package logic

class WorldRef(var world: World) {

    fun moveForward(): World {
        world = world.moveForward()
        return world
    }

    fun turnLeft(): World {
        world = world.turnLeft()
        return world
    }

    fun turnAround(): World {
        world = world.turnAround()
        return world
    }

    fun turnRight(): World {
        world = world.turnRight()
        return world
    }

    fun pickBeeper(): World {
        world = world.pickBeeper()
        return world
    }

    fun dropBeeper(): World {
        world = world.dropBeeper()
        return world
    }

    fun toggleBeeper(x: Int, y: Int) {
        world = world.toggleBeeper(x, y)
    }
}
