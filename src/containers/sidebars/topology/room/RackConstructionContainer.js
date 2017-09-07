import {connect} from "react-redux";
import {startRackConstruction, stopRackConstruction} from "../../../../actions/topology";
import RackConstructionComponent from "../../../../components/sidebars/topology/room/RackConstructionComponent";

const mapStateToProps = state => {
    return {
        inRackConstructionMode: state.construction.inRackConstructionMode,
    };
};

const mapDispatchToProps = dispatch => {
    return {
        onStart: () => dispatch(startRackConstruction()),
        onStop: () => dispatch(stopRackConstruction()),
    };
};

const RackConstructionContainer = connect(
    mapStateToProps,
    mapDispatchToProps
)(RackConstructionComponent);

export default RackConstructionContainer;
